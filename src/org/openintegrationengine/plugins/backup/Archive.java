/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The archive file: a zip, written from a staging directory and read back into one.
 *
 * <p>A zip rather than a tar, and rather than one large XML document, for three reasons
 * that all point the same way. A zip has a central directory, so listing what is inside one
 * and pulling a single file out of it does not mean reading the whole thing -- which is
 * what makes "show me what is in this backup" instant on a 12 GB archive sitting on an SFTP
 * server. Every operating system and every operator already has a tool for it. And the
 * layout inside is the same file-per-object layout that Git Sync and
 * {@code scripts/oie-config-pull.sh} use, so the {@code config/} directory can be lifted
 * straight out of a backup into a configuration repository and back again.
 *
 * <pre>
 *   manifest.properties          what this archive is; see {@link Manifest}
 *   config/...                   one file per object, Git Sync's layout exactly
 *   messages/&lt;channel-id&gt;/...     the engine's own message export, per channel
 *   messages/channels.tsv        channel id, name and count, so ids have names
 * </pre>
 *
 * <h2>Reading one is reading something from outside</h2>
 *
 * <p>An archive being restored has come back from storage this engine does not control, or
 * from a browser upload. So extraction refuses absolute entry names, entries containing
 * {@code ..}, entries that resolve outside the target directory, and symbolic links -- the
 * zip-slip family, which is exactly a path traversal with a friendlier name. It also caps
 * the total extracted size and the number of entries, because a 400 KB zip that expands to
 * fill the disk is a two-line file to write and would take the engine down with it.
 */
final class Archive implements Closeable {

    /**
     * Ceilings for extraction. Generous -- a real backup with a year of messages is
     * legitimately enormous -- but not unbounded, which is the property that matters.
     */
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 5_000_000;
    /**
     * Refuse an entry whose compressed size implies a ratio past this.
     *
     * <p>XML of the kind this archive is full of compresses at roughly 10:1, and a zip bomb
     * runs to 1000:1 and beyond, so 200:1 sits well clear of both.
     */
    private static final int MAX_RATIO = 200;

    private final ZipFile zip;

    private Archive(ZipFile zip) {
        this.zip = zip;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Zips {@code source} into {@code target}.
     *
     * <p>Entries are written in sorted order so that two backups of an unchanged engine
     * produce archives that differ only in their timestamps, which is what makes them
     * comparable. Deflate at the default level rather than maximum: the content is XML, the
     * difference between level 6 and level 9 on it is a couple of percent, and the
     * difference in CPU time on a 10 GB export is not.
     */
    static void write(Path source, Path target) throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> source.relativize(p).toString()));

        try (ZipOutputStream out = new ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(target)))) {
            out.setLevel(Deflater.DEFAULT_COMPRESSION);
            byte[] buffer = new byte[1 << 16];
            for (Path file : files) {
                String name = source.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                out.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    static Archive open(Path file) throws IOException {
        try {
            return new Archive(new ZipFile(file.toFile()));
        } catch (java.util.zip.ZipException e) {
            throw new IOException("this file is not a readable zip archive"
                + (ArchiveCipher.isEncryptedFile(file)
                    ? ": it is an encrypted OIE backup, and the passphrase has not been "
                      + "supplied" : ": " + e.getMessage()), e);
        }
    }

    /** Entry names, in the order the central directory holds them. */
    List<String> entries() {
        List<String> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                out.add(entry.getName());
            }
        }
        return out;
    }

    boolean has(String name) {
        return zip.getEntry(name) != null;
    }

    /** Reads one entry as UTF-8, or null when it is not there. */
    String readText(String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts every entry under {@code prefix} into {@code target}.
     *
     * @param prefix the directory inside the archive, with a trailing slash, or "" for all
     * @return how many files were written
     */
    int extract(String prefix, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);

        long totalBytes = 0;
        int count = 0;
        byte[] buffer = new byte[1 << 16];

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                continue;
            }
            if (++count > MAX_ENTRIES) {
                throw new IOException("this archive declares more than " + MAX_ENTRIES
                    + " files; refusing to extract it");
            }

            Path destination = resolveSafely(root, prefix.isEmpty()
                ? name : name.substring(prefix.length()));
            long declared = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (declared >= 0 && compressed > 0 && declared / compressed > MAX_RATIO) {
                throw new IOException("'" + name + "' expands more than " + MAX_RATIO
                    + " times; refusing to extract it");
            }

            Files.createDirectories(destination.getParent());
            try (InputStream in = zip.getInputStream(entry);
                 OutputStream out = Files.newOutputStream(destination)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    totalBytes += n;
                    if (totalBytes > MAX_EXTRACTED_BYTES) {
                        throw new IOException("this archive expands past "
                            + (MAX_EXTRACTED_BYTES >> 30) + " GB; refusing to extract it");
                    }
                    out.write(buffer, 0, n);
                }
            }
        }
        return count;
    }

    /**
     * Where an entry is allowed to land.
     *
     * <p>The check is on the resolved, normalised path rather than on the name, because
     * every clever spelling of {@code ..} -- {@code a/../../b}, a backslash on Windows, a
     * leading {@code /} -- ends up in the same place once resolved, and comparing there
     * catches all of them at once instead of one string check per trick.
     */
    private static Path resolveSafely(Path root, String name) throws IOException {
        String cleaned = name.replace('\\', '/');
        if (cleaned.isBlank() || cleaned.startsWith("/") || cleaned.contains("\u0000")) {
            throw new IOException("'" + name + "' is not a path this archive may contain");
        }
        Path candidate = root.resolve(cleaned).normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("'" + name + "' would be written outside the restore "
                + "directory; refusing to extract this archive");
        }
        return candidate;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
