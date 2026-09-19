/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A Windows or NAS share, reached over SMB with its own credentials.
 *
 * <p>The other half of the "directory or network path" destination. A share that the
 * platform has already mounted is a directory and {@link LocalDestination} handles it; a
 * share that has not been mounted -- because the engine runs in a container that nobody
 * wants to give {@code CAP_SYS_ADMIN}, or because the credentials belong to this backup job
 * rather than to the host -- is handled here. The path decides which: a UNC path or an
 * {@code smb://} URL comes here, anything else does not.
 *
 * <p>This is the one place the extension bundles a jar. The engine ships {@code jcifs-ng},
 * but in {@code client-lib} and inside the File connector's own extension directory, not on
 * the shared server classpath -- so an extension cannot rely on seeing it. The same version
 * is bundled here, checksum-verified against what Maven Central publishes beside it, which
 * is the arrangement the Git Sync extension uses for JGit.
 *
 * <h2>Credentials are optional</h2>
 *
 * <p>A share with guest access, or one already authenticated at the host level, needs none,
 * and leaving the username empty connects anonymously. When they are given, the password is
 * encrypted with the engine's own key before it is stored and is never returned by the API.
 * The domain is a separate field rather than split off a backslash-separated
 * {@code DOMAIN} and user name, because a username
 * containing a backslash is also how a local account on the NAS is spelled and guessing
 * between the two produces an authentication failure with no clue in it.
 *
 * <h2>SMB1 is not offered</h2>
 *
 * <p>The minimum dialect is SMB2. SMB1 has been off by default in Windows since 2017 and
 * is the protocol behind WannaCry; a NAS old enough to need it is one that should be
 * reached through a mount the operating system makes, where that decision is visible to
 * whoever administers the host rather than buried in an engine's plugin settings.
 */
final class SmbDestination implements Destination {

    private final CIFSContext context;
    /** The directory, as an {@code smb://} URL ending in a slash. */
    private final String directory;
    private final String describe;

    SmbDestination(DestinationConfig config) throws IOException {
        String configured = config.require(Destinations.K_PATH, "The network path");
        this.directory = toSmbUrl(configured);

        String username = config.get(Destinations.K_USERNAME, "");
        String domain = config.get(Destinations.K_DOMAIN, "");
        String password = config.get(Destinations.K_SECRET, "");

        Properties properties = new Properties();
        /*
         * SMB2 at minimum; see the class comment. The maximum is left at 3.1.1, which is
         * what a current Windows Server and every modern NAS negotiate, and what carries
         * encryption in transit.
         */
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        // Signing is required when the server asks for it and not forced when it does not,
        // which is the default; setting it explicitly keeps a future jcifs default change
        // from quietly turning it off.
        properties.setProperty("jcifs.smb.client.signingEnforced", "false");
        properties.setProperty("jcifs.smb.client.responseTimeout", "60000");
        properties.setProperty("jcifs.smb.client.soTimeout", "180000");
        properties.setProperty("jcifs.smb.client.connTimeout", "15000");
        // The transfer is a single large file, so a bigger read/write unit is a real gain
        // rather than a micro-optimisation.
        properties.setProperty("jcifs.smb.client.dfs.disabled", "false");

        CIFSContext base;
        try {
            base = new BaseContext(new PropertyConfiguration(properties));
        } catch (CIFSException e) {
            throw new IOException("could not start the SMB client: " + e.getMessage(), e);
        }
        this.context = username.isEmpty()
            ? base.withAnonymousCredentials()
            : base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password));

        this.describe = "SMB: " + configured
            + (username.isEmpty() ? " (anonymous)"
                : " as " + (domain.isEmpty() ? username : domain + "\\" + username));

        /*
         * Connecting here rather than on the first put means a wrong password is reported
         * when the destination is saved or tested, not at three in the morning.
         *
         * What the share turned out to be is recorded and acted on after the try, rather
         * than thrown from inside it: the catch below wraps an IOException in "could not
         * reach ...", and a throw from inside would be caught by its own handler and
         * described as a connection failure when it is the opposite -- a successful
         * connection to something that is not there.
         */
        boolean exists;
        boolean isDirectory;
        try (SmbFile dir = new SmbFile(directory, context)) {
            exists = dir.exists();
            isDirectory = exists && dir.isDirectory();
        } catch (SmbAuthException e) {
            throw new IOException("the share refused these credentials: " + e.getMessage()
                + (username.isEmpty()
                    ? ". No username was given, so this connected anonymously." : ""), e);
        } catch (MalformedURLException e) {
            throw new IOException("'" + configured + "' is not a usable network path. Use "
                + "\\\\server\\share\\folder or smb://server/share/folder.", e);
        } catch (IOException e) {
            /*
             * jcifs answers an unreachable server with "Failed to connect to server" and
             * nothing else -- no host, no port, no clue that the engine is inside a
             * container with its own DNS. Naming the path and the two things that are
             * actually wrong when this happens turns it back into something actionable.
             */
            throw new IOException("could not reach " + configured + ": " + e.getMessage()
                + ". Check the server name resolves from inside the engine container, and "
                + "that TCP 445 is reachable from it.", e);
        }

        if (!exists) {
            throw new IOException("connected, but the share or directory does not exist: "
                + configured + ". It is not created for you, because creating a tree from a "
                + "typo is how backups end up somewhere nobody looks.");
        }
        if (!isDirectory) {
            throw new IOException(configured + " is a file, not a directory");
        }
    }

    /**
     * Whether a path should be reached over SMB rather than as a directory.
     *
     * <p>Three spellings, because all three are what people actually type: the Windows UNC
     * form, the same thing with forward slashes, and the URL. A single backslash or a drive
     * letter is a local path and is not this.
     */
    static boolean looksLikeShare(String path) {
        if (path == null) {
            return false;
        }
        String t = path.trim();
        return t.startsWith("\\\\") || t.startsWith("//")
            || t.toLowerCase(Locale.ROOT).startsWith("smb://");
    }

    /**
     * Turns any of the accepted spellings into the {@code smb://host/share/dir/} form jcifs
     * needs.
     *
     * <p>The trailing slash is not cosmetic: jcifs treats a URL without one as a file, so a
     * directory listing against it fails with a message about the path not being a
     * directory rather than about the missing slash.
     */
    static String toSmbUrl(String path) {
        String t = path.trim().replace('\\', '/');
        if (t.toLowerCase(Locale.ROOT).startsWith("smb://")) {
            t = t.substring("smb://".length());
        }
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        if (t.contains("..")) {
            throw new IllegalArgumentException("the network path must not contain '..'");
        }
        if (!t.endsWith("/")) {
            t = t + "/";
        }
        if (t.indexOf('/') == t.length() - 1) {
            throw new IllegalArgumentException("a network path needs a share as well as a "
                + "server: \\\\server\\share, not \\\\server");
        }
        return "smb://" + t;
    }

    @Override
    public String describe() {
        return describe;
    }

    @Override
    public void put(String name, Path file) throws IOException {
        String target = Destinations.requireSimpleName(name);
        /*
         * Written under a temporary name and renamed into place, the same as every other
         * destination here: a listing taken during a slow transfer must never show a
         * truncated archive under its final name, because the next thing to read that
         * listing is retention.
         */
        String temp = target + ".part";
        try (SmbFile out = new SmbFile(directory + temp, context);
             OutputStream sink = out.getOutputStream();
             InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                sink.write(buffer, 0, n);
            }
        }
        try (SmbFile existing = new SmbFile(directory + target, context)) {
            if (existing.exists()) {
                existing.delete();
            }
        } catch (IOException e) {
            // Nothing there to replace, which is the normal case; the rename below is what
            // actually has to succeed.
        }
        try (SmbFile from = new SmbFile(directory + temp, context);
             SmbFile to = new SmbFile(directory + target, context)) {
            from.renameTo(to);
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        try (SmbFile dir = new SmbFile(directory, context)) {
            SmbFile[] files = dir.listFiles();
            if (files != null) {
                for (SmbFile file : files) {
                    try (SmbFile f = file) {
                        String name = f.getName();
                        if (!f.isFile() || !Destinations.isArchiveName(name)) {
                            continue;
                        }
                        out.add(new RemoteFile(name, f.length(),
                            Instant.ofEpochMilli(f.lastModified())));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        String remote = Destinations.requireSimpleName(name);
        try (SmbFile file = new SmbFile(directory + remote, context);
             InputStream in = file.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        String remote = Destinations.requireSimpleName(name);
        try (SmbFile file = new SmbFile(directory + remote, context)) {
            if (file.exists()) {
                file.delete();
            }
        }
    }

    @Override
    public String probe() throws IOException {
        String marker = ".oie-backup-probe";
        try (SmbFile file = new SmbFile(directory + marker, context);
             OutputStream out = file.getOutputStream()) {
            out.write("probe\n".getBytes(StandardCharsets.UTF_8));
        } catch (SmbAuthException e) {
            throw new IOException("connected, but the share refused a write: "
                + e.getMessage(), e);
        }

        int found = list().size();

        long free = -1;
        try (SmbFile dir = new SmbFile(directory, context)) {
            free = dir.getDiskFreeSpace();
        } catch (IOException e) {
            // Not every server answers the free-space query, and it is a nicety here.
            free = -1;
        }

        try (SmbFile file = new SmbFile(directory + marker, context)) {
            file.delete();
        } catch (IOException e) {
            return "Wrote a probe file to the share and listed " + found
                + " archive(s), but could not remove it: " + e.getMessage()
                + ". Retention will not be able to remove old archives from here.";
        }
        return "Connected to the share, wrote and removed a probe file. " + found
            + " archive(s) already there."
            + (free > 0 ? " " + Destinations.bytes(free) + " free." : "");
    }

    @Override
    public void close() throws IOException {
        try {
            context.close();
        } catch (CIFSException e) {
            throw new IOException("could not close the SMB session: " + e.getMessage(), e);
        }
    }

    /**
     * Fixed vectors for the path conversion, run by {@code build.sh}.
     *
     * <p>A path that converts wrongly does not fail loudly -- it connects to the wrong
     * place, or refuses with a message about the path not being a directory, which sends
     * people to check the share's permissions rather than their own typing.
     *
     * @return null when every vector holds, otherwise the first failure
     */
    static String selfTest() {
        String[][] cases = {
            {"\\\\nas\\backups", "smb://nas/backups/"},
            {"\\\\nas\\backups\\oie", "smb://nas/backups/oie/"},
            {"//nas/backups/oie", "smb://nas/backups/oie/"},
            {"smb://nas/backups/oie", "smb://nas/backups/oie/"},
            {"smb://nas/backups/oie/", "smb://nas/backups/oie/"},
            {"  \\\\nas\\backups\\oie  ", "smb://nas/backups/oie/"},
        };
        for (String[] c : cases) {
            String got = toSmbUrl(c[0]);
            if (!c[1].equals(got)) {
                return "'" + c[0].trim() + "' became '" + got + "', expected '" + c[1] + "'";
            }
        }
        if (!looksLikeShare("\\\\nas\\backups") || !looksLikeShare("//nas/backups")
            || !looksLikeShare("smb://nas/backups") || !looksLikeShare("SMB://nas/backups")) {
            return "a share path was not recognised as one";
        }
        if (looksLikeShare("/mnt/backups") || looksLikeShare("C:\\backups")
            || looksLikeShare("") || looksLikeShare(null)) {
            return "a local path was mistaken for a share";
        }
        try {
            toSmbUrl("\\\\nas");
            return "a path with no share should have been refused";
        } catch (IllegalArgumentException expected) {
            // Right answer.
        }
        try {
            toSmbUrl("\\\\nas\\backups\\..\\..\\etc");
            return "a traversing path should have been refused";
        } catch (IllegalArgumentException expected) {
            // Right answer.
        }
        return null;
    }
}
