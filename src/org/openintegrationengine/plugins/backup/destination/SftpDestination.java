/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Vector;

/**
 * SFTP, over the engine's own JSch.
 *
 * <p>No jar is bundled: the engine ships {@code jsch-2.27.7} (the mwiede fork, which keeps
 * the {@code com.jcraft.jsch} package), the built-in File connector uses it for its SFTP
 * scheme, and this repository's SFTP connector uses it too. So a backup sent over SFTP
 * negotiates exactly what every other SFTP connection out of this engine negotiates, which
 * matters the day a server drops {@code ssh-rsa}: one answer for the whole engine, not two.
 *
 * <h2>The host key is checked by default</h2>
 *
 * <p>SSH has no certificate authorities, so the only meaningful verification is against a
 * key already held. Accepting any key encrypts the transfer and proves nothing about who
 * is on the other end -- and the thing being handed over is a complete copy of this
 * engine's configuration and, usually, its message history. So the default policy is
 * {@code known_hosts} and trusting any key is a choice with a warning next to it, not the
 * path of least resistance.
 */
final class SftpDestination implements Destination {

    private static final int CONNECT_TIMEOUT_MS = 15000;

    private final String host;
    private final int port;
    private final String username;
    private final String directory;
    private final String describe;

    private final Session session;
    private final ChannelSftp channel;

    SftpDestination(DestinationConfig config) throws IOException {
        this.host = config.require(Destinations.K_HOST, "The host");
        this.port = config.getInt(Destinations.K_PORT, 22);
        this.username = config.require(Destinations.K_USERNAME, "The username");
        this.directory = Destinations.normalisePrefix(config.get(Destinations.K_PATH, ""));
        this.describe = "SFTP: " + username + "@" + host + ":" + port
            + "/" + (directory.isEmpty() ? "." : directory);

        String authMethod = config.get(Destinations.K_AUTH_METHOD, "password");
        String knownHosts = config.raw(Destinations.K_KNOWN_HOSTS);
        String policy = config.get(Destinations.K_HOST_KEY_POLICY, "known_hosts");

        Session s = null;
        try {
            JSch jsch = new JSch();

            if ("key".equalsIgnoreCase(authMethod)) {
                String key = config.raw(Destinations.K_PRIVATE_KEY);
                if (key.isBlank()) {
                    throw new IllegalArgumentException("The private key is required");
                }
                String passphrase = config.get(Destinations.K_PASSPHRASE, "");
                jsch.addIdentity("oie-backup",
                    key.getBytes(StandardCharsets.UTF_8), null,
                    passphrase.isEmpty() ? null : passphrase.getBytes(StandardCharsets.UTF_8));
            }

            boolean trustAny = "trust_any".equalsIgnoreCase(policy);
            if (!trustAny) {
                if (knownHosts.isBlank()) {
                    throw new IllegalArgumentException(
                        "known_hosts lines are required, or the host key policy must be set "
                        + "to trust any key -- which encrypts the transfer but does not "
                        + "prove which server is receiving the backup. Run "
                        + "'ssh-keyscan -p " + port + " " + host + "' and paste the output.");
                }
                jsch.setKnownHosts(new ByteArrayInputStream(
                    knownHosts.getBytes(StandardCharsets.UTF_8)));
            }

            s = jsch.getSession(username, host, port);
            if ("password".equalsIgnoreCase(authMethod)) {
                s.setPassword(config.require(Destinations.K_SECRET, "The password"));
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            } else {
                s.setConfig("PreferredAuthentications", "publickey");
            }
            s.setConfig("StrictHostKeyChecking", trustAny ? "no" : "yes");
            s.connect(CONNECT_TIMEOUT_MS);

            ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
            c.connect(CONNECT_TIMEOUT_MS);

            this.session = s;
            this.channel = c;
        } catch (IllegalArgumentException e) {
            if (s != null) {
                s.disconnect();
            }
            throw e;
        } catch (Exception e) {
            if (s != null) {
                s.disconnect();
            }
            // JSch's own messages -- "Auth fail", "reject HostKey", "UnknownHostKey",
            // "invalid privatekey" -- are the ones that say which of the four things is
            // wrong, so they are carried through rather than replaced.
            throw new IOException("could not connect to sftp://" + username + "@" + host
                + ":" + port + ": " + rootMessage(e), e);
        }

        try {
            if (!directory.isEmpty()) {
                channel.cd(directory);
            }
        } catch (SftpException e) {
            close();
            throw new IOException("connected, but could not change to '" + directory
                + "': " + e.getMessage()
                + ". The path is relative to the login directory unless it starts from the "
                + "root; it is not created automatically, because creating a directory tree "
                + "from a typo is how backups end up somewhere nobody looks.", e);
        }
    }

    @Override
    public String describe() {
        return describe;
    }

    @Override
    public void put(String name, Path file) throws IOException {
        String target = Destinations.requireSimpleName(name);
        /*
         * Uploaded under a temporary name and renamed into place, so a listing taken during
         * a slow transfer never shows a truncated archive under its final name. The delete
         * before the rename is for the servers whose rename refuses an existing target;
         * failing it is fine, because then there was nothing there.
         */
        String temp = target + ".part";
        try (InputStream in = Files.newInputStream(file)) {
            channel.put(in, temp);
        } catch (SftpException e) {
            throw new IOException("could not write " + temp + ": " + e.getMessage(), e);
        }
        try {
            channel.rm(target);
        } catch (SftpException ignored) {
            // Nothing there to replace, which is the normal case.
        }
        try {
            channel.rename(temp, target);
        } catch (SftpException e) {
            throw new IOException("uploaded, but could not rename " + temp + " to " + target
                + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = channel.ls(".");
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                SftpATTRS attrs = entry.getAttrs();
                if (attrs.isDir() || attrs.isLink() || !Destinations.isArchiveName(name)) {
                    continue;
                }
                out.add(new RemoteFile(name, attrs.getSize(),
                    // getMTime is seconds since the epoch, and it is an int, so it is read
                    // as unsigned to stay right past 2038.
                    Instant.ofEpochSecond(Integer.toUnsignedLong(attrs.getMTime()))));
            }
        } catch (SftpException e) {
            throw new IOException("could not list the remote directory: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        String remote = Destinations.requireSimpleName(name);
        try (OutputStream out = Files.newOutputStream(target)) {
            channel.get(remote, out);
        } catch (SftpException e) {
            throw new IOException("could not download " + remote + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            channel.rm(Destinations.requireSimpleName(name));
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return;
            }
            throw new IOException("could not delete " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String probe() throws IOException {
        String marker = ".oie-backup-probe";
        try {
            channel.put(new ByteArrayInputStream("probe\n".getBytes(StandardCharsets.UTF_8)),
                marker);
        } catch (SftpException e) {
            throw new IOException("connected as " + username + ", but could not write to "
                + where() + ": " + e.getMessage(), e);
        }
        int found = list().size();
        try {
            channel.rm(marker);
        } catch (SftpException e) {
            return "Wrote a probe file to " + where() + " and listed " + found
                + " archive(s), but could not remove the probe: " + e.getMessage()
                + ". Retention will not be able to remove old archives from here.";
        }
        return "Connected as " + username + ", wrote and removed a probe file in " + where()
            + ". " + found + " archive(s) already there.";
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
    }

    private String where() {
        try {
            return channel.pwd();
        } catch (SftpException e) {
            return directory.isEmpty() ? "the login directory" : directory;
        }
    }

    /**
     * The innermost message of a JSch failure.
     *
     * <p>JSch wraps an unresolvable host in a {@code JSchException} whose own message is
     * just the class name of the cause, so without this an operator gets
     * "java.net.UnknownHostException" where they needed "backup.example.org".
     */
    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        String own = t.getMessage();
        return own == null || own.isBlank() || own.equals(message)
            ? message : own + " (" + message + ")";
    }

    /** Normalises the auth method token the form posts. */
    static String normaliseAuthMethod(String value) {
        return value == null ? "password" : value.trim().toLowerCase(Locale.ROOT);
    }
}
