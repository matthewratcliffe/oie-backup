/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

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

/**
 * FTP, and FTP over TLS.
 *
 * <p>On the engine's own {@code commons-net}, which the built-in File connector already
 * uses for its FTP scheme, so nothing is bundled and an FTP server that works for a channel
 * works here.
 *
 * <h2>Why plain FTP is offered at all</h2>
 *
 * <p>It sends the password in clear and the file in clear, and for a backup that file is
 * this engine's configuration and its message history. The honest position is that nobody
 * should choose it over the internet. It is here because the deployments that ask for FTP
 * are the ones that have an appliance or a hospital-side drop box that speaks nothing else,
 * on a segment where the alternative to plain FTP is no backup at all -- and that trade is
 * the operator's to make, not this plugin's to refuse on their behalf.
 *
 * <p>What the plugin does instead of refusing: the console marks plain FTP as unencrypted
 * wherever it is shown, {@link #probe()} says so in its result, the connection is logged at
 * {@code WARN} the first time it is used, and the archive encryption offered beside the
 * destination is the answer worth reaching for -- an encrypted archive over plain FTP
 * exposes its name and its size and nothing else.
 *
 * <h2>Two sockets</h2>
 *
 * <p>FTP runs a control connection and a separate data connection, which is why it is the
 * destination most likely to half-work through a firewall: the login succeeds, the listing
 * hangs. Passive mode is the default because it is the one that survives the engine being
 * behind NAT, which in a container it always is.
 */
final class FtpDestination implements Destination {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int DATA_TIMEOUT_MS = 120000;

    private final FTPClient client;
    private final String host;
    private final int port;
    private final String username;
    private final String directory;
    private final String security;

    FtpDestination(DestinationConfig config) throws IOException {
        this.host = config.require(Destinations.K_HOST, "The host");
        this.username = config.require(Destinations.K_USERNAME, "The username");
        this.directory = Destinations.normalisePrefix(config.get(Destinations.K_PATH, ""));

        String mode = config.get(Destinations.K_FTPS, "none").toLowerCase(Locale.ROOT);
        boolean implicit = "implicit".equals(mode);
        boolean explicit = "explicit".equals(mode);
        this.security = implicit ? "FTPS (implicit)" : explicit ? "FTPS (explicit)" : "FTP (no TLS)";
        this.port = config.getInt(Destinations.K_PORT, implicit ? 990 : 21);

        FTPClient c = implicit || explicit ? new FTPSClient(implicit) : new FTPClient();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setDefaultTimeout(CONNECT_TIMEOUT_MS);

        try {
            c.connect(host, port);
            if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
                throw new IOException("the server refused the connection: " + c.getReplyString());
            }
            // Explicit FTPS sends AUTH TLS as part of connect(), before the credentials go
            // anywhere -- which is the entire point of it, and the reason execAUTH is not
            // called here: doing it twice makes the second one an error on some servers.
            if (!c.login(username, config.require(Destinations.K_SECRET, "The password"))) {
                throw new IOException("the server refused the credentials: " + c.getReplyString());
            }
            if (c instanceof FTPSClient) {
                FTPSClient tls = (FTPSClient) c;
                // Without PROT P the data connection -- the one carrying the backup -- is
                // in clear even though the control connection is encrypted.
                tls.execPBSZ(0);
                tls.execPROT("P");
            }
            c.setSoTimeout(DATA_TIMEOUT_MS);
            c.setDataTimeout(java.time.Duration.ofMillis(DATA_TIMEOUT_MS));
            c.setFileType(FTP.BINARY_FILE_TYPE);
            if (config.getBool(Destinations.K_PASSIVE, true)) {
                c.enterLocalPassiveMode();
            } else {
                c.enterLocalActiveMode();
            }
            if (!directory.isEmpty() && !c.changeWorkingDirectory(directory)) {
                throw new IOException("connected, but could not change to '" + directory
                    + "': " + c.getReplyString()
                    + ". The directory is not created automatically, because creating a "
                    + "tree from a typo is how backups end up somewhere nobody looks.");
            }
        } catch (IOException e) {
            quietlyDisconnect(c);
            throw new IOException("could not connect to " + scheme() + "://" + username + "@"
                + host + ":" + port + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            quietlyDisconnect(c);
            throw e;
        }
        this.client = c;
    }

    @Override
    public String describe() {
        return security + ": " + username + "@" + host + ":" + port
            + "/" + (directory.isEmpty() ? "." : directory);
    }

    /** Whether this connection encrypts what it carries. The console shows this. */
    boolean encrypted() {
        return client instanceof FTPSClient;
    }

    @Override
    public void put(String name, Path file) throws IOException {
        String target = Destinations.requireSimpleName(name);
        String temp = target + ".part";
        try (InputStream in = Files.newInputStream(file)) {
            if (!client.storeFile(temp, in)) {
                throw new IOException("the server refused the upload: " + client.getReplyString());
            }
        }
        // Best effort: a server whose rename refuses an existing target needs this, and a
        // server with nothing there answers "no such file", which is the same outcome.
        client.deleteFile(target);
        if (!client.rename(temp, target)) {
            throw new IOException("uploaded, but could not rename " + temp + " to " + target
                + ": " + client.getReplyString());
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        FTPFile[] files = client.listFiles();
        if (files != null) {
            for (FTPFile f : files) {
                if (f == null || !f.isFile() || !Destinations.isArchiveName(f.getName())) {
                    continue;
                }
                Instant modified = f.getTimestamp() == null ? null
                    : f.getTimestamp().toInstant();
                out.add(new RemoteFile(f.getName(), f.getSize(), modified));
            }
        }
        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        String remote = Destinations.requireSimpleName(name);
        try (OutputStream out = Files.newOutputStream(target)) {
            if (!client.retrieveFile(remote, out)) {
                throw new IOException("could not download " + remote + ": "
                    + client.getReplyString());
            }
        }
    }

    @Override
    public void delete(String name) throws IOException {
        String remote = Destinations.requireSimpleName(name);
        if (!client.deleteFile(remote) && !FTPReply.isPositiveCompletion(client.getReplyCode())) {
            // 550 is "no such file" as well as "permission denied", and a retention pass
            // asking twice must not fail, so a missing file is checked for rather than
            // inferred from the code.
            if (exists(remote)) {
                throw new IOException("could not delete " + remote + ": "
                    + client.getReplyString());
            }
        }
    }

    @Override
    public String probe() throws IOException {
        String marker = ".oie-backup-probe";
        if (!client.storeFile(marker,
                new ByteArrayInputStream("probe\n".getBytes(StandardCharsets.UTF_8)))) {
            throw new IOException("connected as " + username + ", but could not write to "
                + client.printWorkingDirectory() + ": " + client.getReplyString());
        }
        int found = list().size();
        String warning = encrypted() ? ""
            : " This connection is plain FTP: the password and the archive both cross the "
                + "network in clear. Encrypt the archive, or use FTPS or SFTP.";
        if (!client.deleteFile(marker)) {
            return "Wrote a probe file and listed " + found + " archive(s), but could not "
                + "remove the probe: " + client.getReplyString()
                + " Retention will not be able to remove old archives from here." + warning;
        }
        return "Connected as " + username + " over " + security + ", wrote and removed a "
            + "probe file in " + client.printWorkingDirectory() + ". " + found
            + " archive(s) already there." + warning;
    }

    @Override
    public void close() {
        quietlyDisconnect(client);
    }

    private boolean exists(String name) throws IOException {
        FTPFile[] files = client.listFiles(name);
        return files != null && files.length > 0;
    }

    private String scheme() {
        return client instanceof FTPSClient ? "ftps" : "ftp";
    }

    private static void quietlyDisconnect(FTPClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.logout();
                c.disconnect();
            }
        } catch (IOException ignored) {
            // Closing a connection that is already gone is not a failure worth reporting;
            // the operation that mattered has already succeeded or thrown.
        }
    }
}
