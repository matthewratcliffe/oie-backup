/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Optional encryption for the archive, with a passphrase the engine does not keep.
 *
 * <p>The case for it is simple: a backup holds every credential in the configuration map,
 * the SMTP password out of the server settings, and -- when messages are included -- the
 * clinical content of everything the engine has processed. It is then handed to somewhere
 * that is, by design, not this engine: a bucket, a share, somebody's SFTP server. S3 and
 * Azure encrypt at rest on their own side, which protects against a stolen disk in a data
 * centre and not against a misconfigured bucket policy, a support engineer with console
 * access, or an FTP server on a hospital network. Encrypting before the archive leaves
 * covers all of those, and costs one passphrase.
 *
 * <h2>The format</h2>
 *
 * <pre>
 *   "OIEBACKUP" (9 bytes)   magic, so a file can be identified without its name
 *   1 byte                  format version, currently 1
 *   1 byte                  key derivation, currently 1 = PBKDF2WithHmacSHA256
 *   4 bytes                 iteration count, big-endian
 *   1 byte + n              salt length and salt
 *   1 byte + n              nonce prefix length and prefix
 *   4 bytes                 plaintext chunk size, big-endian
 *   then, repeated:
 *     4 bytes               ciphertext length of this chunk, big-endian
 *     n bytes               AES-256-GCM ciphertext, tag included
 * </pre>
 *
 * <p>AES-GCM, chunked rather than applied to the whole file, because GCM authenticates the
 * whole of what it covers before yielding any of it: one 40 GB chunk would mean holding 40
 * GB before a single byte could be written. Each chunk gets its own nonce --
 * {@code prefix || counter} -- which is what makes reusing a nonce under one key
 * impossible without reusing the whole header, and the header is regenerated per archive.
 *
 * <p><b>Truncation is detected.</b> Each chunk's additional authenticated data is the file
 * header, the chunk's index, and a flag saying whether it is the last one. So a file cut
 * short does not decrypt to a valid shorter archive -- which is the failure this is really
 * guarding against, since a backup that restores successfully but silently missing its last
 * thousand messages is worse than one that refuses to restore.
 *
 * <h2>What this does not do</h2>
 *
 * <p>There is no key escrow, no recovery, and no copy of the passphrase anywhere. Lose it
 * and the archive is gone -- which is the property that makes the encryption worth
 * anything, and the reason the console makes the operator type it twice and says this in
 * plain words rather than in a tooltip. The passphrase <i>is</i> stored, encrypted with the
 * engine's own key, so that scheduled backups can run unattended; that means an engine that
 * is still running can always read its own archives, and an engine that has been rebuilt
 * from nothing cannot. Which is exactly the situation the passphrase needs to survive
 * independently of this engine.
 */
final class ArchiveCipher {

    private static final byte[] MAGIC = "OIEBACKUP".getBytes(StandardCharsets.US_ASCII);
    private static final byte FORMAT_VERSION = 1;
    private static final byte KDF_PBKDF2_SHA256 = 1;

    /**
     * 600,000, the figure OWASP gives for PBKDF2-HMAC-SHA256.
     *
     * <p>Stored in the header rather than assumed, so raising it later does not make every
     * existing archive undecryptable -- the reader takes the count from the file.
     */
    private static final int ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int TAG_BITS = 128;
    /** 1 MiB of plaintext per chunk: small enough to stream, large enough that the 16-byte
     *  tag and 4-byte length are noise rather than overhead. */
    private static final int CHUNK_BYTES = 1 << 20;

    /** The extension added to an encrypted archive, and the way one is recognised. */
    static final String SUFFIX = ".enc";

    private ArchiveCipher() {
    }

    static boolean isEncryptedName(String name) {
        return name != null && name.endsWith(SUFFIX);
    }

    /** Whether a file starts with this format's magic, regardless of what it is called. */
    static boolean isEncryptedFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(MAGIC.length);
            return Arrays.equals(magic, MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Encrypt
    // ------------------------------------------------------------------

    static void encrypt(Path plain, Path target, char[] passphrase) throws IOException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        random.nextBytes(salt);
        random.nextBytes(noncePrefix);

        byte[] header = header(salt, noncePrefix);
        SecretKey key = deriveKey(passphrase, salt, ITERATIONS);

        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(plain));
             DataOutputStream out = new DataOutputStream(
                 new java.io.BufferedOutputStream(Files.newOutputStream(target)))) {

            out.write(header);

            byte[] buffer = new byte[CHUNK_BYTES];
            long index = 0;
            int filled = readFully(in, buffer);
            while (true) {
                int next = filled < buffer.length ? -1 : peekFill(in, buffer.length);
                // A chunk is final when the read came up short, or when the next read finds
                // nothing. Knowing which chunk is last is what makes truncation detectable.
                boolean last = filled < buffer.length || next <= 0;

                byte[] chunk = encryptChunk(key, noncePrefix, index, header, last,
                    buffer, filled);
                out.writeInt(chunk.length);
                out.write(chunk);

                if (last) {
                    break;
                }
                System.arraycopy(PEEKED.get(), 0, buffer, 0, next);
                filled = next;
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("could not encrypt the archive: " + e.getMessage(), e);
        } finally {
            PEEKED.remove();
        }
    }

    /**
     * Look-ahead, so the last chunk can be marked as last while it is being written.
     *
     * <p>A thread local rather than a field because {@link ArchiveCipher} has no instances,
     * and a static buffer would be shared between a scheduled backup and one somebody
     * started from the console at the same moment.
     */
    private static final ThreadLocal<byte[]> PEEKED =
        ThreadLocal.withInitial(() -> new byte[CHUNK_BYTES]);

    private static int peekFill(InputStream in, int size) throws IOException {
        byte[] buffer = PEEKED.get();
        int n = readFully(in, buffer, size);
        return n;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        return readFully(in, buffer, buffer.length);
    }

    private static int readFully(InputStream in, byte[] buffer, int limit) throws IOException {
        int total = 0;
        while (total < limit) {
            int n = in.read(buffer, total, limit - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static byte[] encryptChunk(SecretKey key, byte[] noncePrefix, long index,
                                       byte[] header, boolean last, byte[] plain, int length)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
            new GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)));
        cipher.updateAAD(aad(header, index, last));
        return cipher.doFinal(plain, 0, length);
    }

    // ------------------------------------------------------------------
    // Decrypt
    // ------------------------------------------------------------------

    /**
     * @throws BadPassphraseException when the passphrase is wrong, which is by far the most
     *                                likely reason this fails and deserves its own answer
     *                                rather than a generic "could not decrypt"
     */
    static void decrypt(Path encrypted, Path target, char[] passphrase) throws IOException {
        try (DataInputStream in = new DataInputStream(
                 new java.io.BufferedInputStream(Files.newInputStream(encrypted)));
             OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target))) {

            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("this file is not an encrypted OIE backup archive");
            }
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IOException("this archive is in encryption format version " + version
                    + ", which this version of the plugin cannot read");
            }
            int kdf = in.readUnsignedByte();
            if (kdf != KDF_PBKDF2_SHA256) {
                throw new IOException("unknown key derivation " + kdf);
            }
            int iterations = in.readInt();
            byte[] salt = in.readNBytes(in.readUnsignedByte());
            byte[] noncePrefix = in.readNBytes(in.readUnsignedByte());
            int chunkSize = in.readInt();
            if (iterations <= 0 || salt.length == 0 || noncePrefix.length == 0
                || chunkSize <= 0 || chunkSize > 64 << 20) {
                throw new IOException("the archive header is not usable; the file is damaged");
            }

            byte[] header = headerFor(version, kdf, iterations, salt, noncePrefix, chunkSize);
            SecretKey key = deriveKey(passphrase, salt, iterations);

            long index = 0;
            boolean sawFinal = false;
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                if (length <= 0 || length > chunkSize + 64) {
                    throw new IOException("the archive is damaged: chunk " + index
                        + " declares an impossible length");
                }
                byte[] chunk = in.readNBytes(length);
                if (chunk.length != length) {
                    throw new IOException("the archive is truncated: chunk " + index
                        + " is incomplete");
                }
                /*
                 * The final flag is part of the authenticated data, so which of the two it
                 * is cannot be guessed -- it is tried both ways and only one can verify.
                 * Trying "not final" first is the common case and costs one GCM verify.
                 */
                byte[] plain = tryDecryptChunk(key, noncePrefix, index, header, false, chunk);
                if (plain == null) {
                    plain = tryDecryptChunk(key, noncePrefix, index, header, true, chunk);
                    sawFinal = plain != null;
                }
                if (plain == null) {
                    if (index == 0) {
                        throw new BadPassphraseException();
                    }
                    throw new IOException("the archive is damaged or has been altered: "
                        + "chunk " + index + " does not authenticate");
                }
                out.write(plain);
                if (sawFinal) {
                    break;
                }
                index++;
            }
            if (!sawFinal) {
                throw new IOException("the archive is truncated: it ends without its final "
                    + "chunk, so it is missing data. Restoring from it would silently lose "
                    + "whatever was cut off.");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("could not decrypt the archive: " + e.getMessage(), e);
        }
    }

    private static byte[] tryDecryptChunk(SecretKey key, byte[] noncePrefix, long index,
                                          byte[] header, boolean last, byte[] chunk)
            throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)));
            cipher.updateAAD(aad(header, index, last));
            return cipher.doFinal(chunk);
        } catch (javax.crypto.AEADBadTagException e) {
            // Expected: either the wrong final flag, or the wrong passphrase. The caller
            // decides which by whether the other flag works.
            return null;
        }
    }

    /** The passphrase did not decrypt the archive. Nothing else is knowable from a tag. */
    static final class BadPassphraseException extends IOException {
        private static final long serialVersionUID = 1L;

        BadPassphraseException() {
            super("the passphrase does not decrypt this archive");
        }
    }

    // ------------------------------------------------------------------
    // Header, key, nonce
    // ------------------------------------------------------------------

    private static byte[] header(byte[] salt, byte[] noncePrefix) {
        return headerFor(FORMAT_VERSION, KDF_PBKDF2_SHA256, ITERATIONS, salt, noncePrefix,
            CHUNK_BYTES);
    }

    private static byte[] headerFor(int version, int kdf, int iterations, byte[] salt,
                                    byte[] noncePrefix, int chunkSize) {
        ByteBuffer b = ByteBuffer.allocate(MAGIC.length + 1 + 1 + 4 + 1 + salt.length + 1
            + noncePrefix.length + 4);
        b.put(MAGIC);
        b.put((byte) version);
        b.put((byte) kdf);
        b.putInt(iterations);
        b.put((byte) salt.length);
        b.put(salt);
        b.put((byte) noncePrefix.length);
        b.put(noncePrefix);
        b.putInt(chunkSize);
        return b.array();
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws IOException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IOException("a passphrase is required to encrypt or decrypt an archive");
        }
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
            try {
                return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            } finally {
                // Clears the copy PBEKeySpec keeps; the caller's array is its own business.
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("could not derive a key from the passphrase: "
                + e.getMessage(), e);
        }
    }

    private static byte[] nonce(byte[] prefix, long index) {
        // 12 bytes, which is the size GCM is defined for and the only one that avoids an
        // extra hashing step inside the cipher.
        ByteBuffer b = ByteBuffer.allocate(12);
        b.put(prefix, 0, Math.min(prefix.length, 8));
        b.position(8);
        b.putInt((int) index);
        return b.array();
    }

    private static byte[] aad(byte[] header, long index, boolean last) {
        ByteBuffer b = ByteBuffer.allocate(header.length + 8 + 1);
        b.put(header);
        b.putLong(index);
        b.put((byte) (last ? 1 : 0));
        return b.array();
    }

    // ------------------------------------------------------------------
    // Self test, run by build.sh
    // ------------------------------------------------------------------

    /**
     * Round trip, wrong passphrase, and truncation, asserted before the extension is
     * packaged.
     *
     * <p>Encryption is the other part of this plugin whose failure is silent: an archive
     * that encrypts but does not decrypt is discovered on the day it is needed, by
     * definition. So the three cases that matter are exercised at build time against real
     * files rather than left to the first restore.
     *
     * @return null when every case holds, otherwise the first failure
     */
    static String selfTest() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("oie-backup-cipher-test");
            Path plain = dir.resolve("plain.bin");
            Path sealed = dir.resolve("sealed.enc");
            Path opened = dir.resolve("opened.bin");

            // Deliberately spans several chunks and does not end on a chunk boundary, so
            // the final-chunk logic is actually exercised.
            byte[] content = new byte[CHUNK_BYTES * 2 + 12345];
            new SecureRandom(new byte[] {1, 2, 3}).nextBytes(content);
            Files.write(plain, content);

            char[] passphrase = "correct horse battery staple".toCharArray();
            encrypt(plain, sealed, passphrase);
            if (Files.size(sealed) <= Files.size(plain)) {
                return "the encrypted file is not larger than the plaintext, which it must be";
            }
            if (!isEncryptedFile(sealed)) {
                return "the magic is not recognised in a file this class just wrote";
            }

            decrypt(sealed, opened, passphrase);
            if (!Arrays.equals(content, Files.readAllBytes(opened))) {
                return "a round trip did not return the original bytes";
            }

            try {
                decrypt(sealed, opened, "wrong passphrase".toCharArray());
                return "a wrong passphrase was accepted";
            } catch (BadPassphraseException expected) {
                // Right answer, and specifically this exception rather than a generic one.
            }

            byte[] sealedBytes = Files.readAllBytes(sealed);
            Path cut = dir.resolve("cut.enc");
            Files.write(cut, Arrays.copyOf(sealedBytes, sealedBytes.length - CHUNK_BYTES));
            try {
                decrypt(cut, opened, passphrase);
                return "a truncated archive decrypted without complaint";
            } catch (IOException expected) {
                if (expected instanceof BadPassphraseException) {
                    return "a truncated archive was reported as a wrong passphrase";
                }
            }

            Path bent = dir.resolve("bent.enc");
            byte[] altered = sealedBytes.clone();
            altered[altered.length / 2] ^= 0x40;
            Files.write(bent, altered);
            try {
                decrypt(bent, opened, passphrase);
                return "an altered archive decrypted without complaint";
            } catch (IOException expected) {
                // Right answer.
            }

            return null;
        } catch (Exception e) {
            return "unexpected failure: " + e;
        } finally {
            if (dir != null) {
                deleteTree(dir);
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temporary files the OS will reclaim; not worth failing a build over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
