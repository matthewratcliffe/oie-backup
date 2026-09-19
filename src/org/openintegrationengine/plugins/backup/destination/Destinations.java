/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which destinations exist, how one is built from its settings, and the two naming rules
 * every one of them relies on.
 */
public final class Destinations {

    private Destinations() {
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /** Kept on this engine, in the plugin's own directory, and downloaded from the console. */
    public static final String KEEP = "keep";
    /**
     * A directory or a network path.
     *
     * <p>One destination with two implementations behind it, chosen by the shape of the
     * path. A directory the engine can already write to -- a volume, a mount the platform
     * made -- goes through {@link LocalDestination}. A UNC path or an {@code smb://} URL
     * goes through {@link SmbDestination}, which speaks SMB itself and can carry its own
     * credentials. One entry in the list rather than two, because from the operator's side
     * it is one question ("where should this go?") and the answer they type already says
     * which of the two it is.
     */
    public static final String LOCAL = "local";
    /** Amazon S3, and anything that speaks its API: Spaces, MinIO, Wasabi, Ceph, R2. */
    public static final String S3 = "s3";
    /** Azure Blob Storage, by account key or by shared access signature. */
    public static final String AZURE = "azure";
    public static final String SFTP = "sftp";
    /** FTP, optionally over TLS. See {@link FtpDestination} for why plain FTP is allowed. */
    public static final String FTP = "ftp";

    // Setting keys. Declared here so that a typo is a compile error in one place rather
    // than a silent default in six.
    public static final String K_PATH = "path";
    public static final String K_HOST = "host";
    public static final String K_PORT = "port";
    public static final String K_USERNAME = "username";
    public static final String K_SECRET = "secret";
    public static final String K_BUCKET = "bucket";
    public static final String K_PREFIX = "prefix";
    public static final String K_REGION = "region";
    public static final String K_ENDPOINT = "endpoint";
    public static final String K_ACCESS_KEY = "accessKeyId";
    public static final String K_PATH_STYLE = "pathStyle";
    public static final String K_STORAGE_CLASS = "storageClass";
    public static final String K_SSE = "serverSideEncryption";
    public static final String K_SSE_KMS_KEY = "kmsKeyId";
    public static final String K_ACCOUNT = "account";
    public static final String K_CONTAINER = "container";
    public static final String K_SAS = "sasToken";
    public static final String K_AZURE_AUTH = "azureAuth";
    public static final String K_AUTH_METHOD = "authMethod";
    public static final String K_PRIVATE_KEY = "privateKey";
    public static final String K_PASSPHRASE = "passphrase";
    public static final String K_KNOWN_HOSTS = "knownHosts";
    public static final String K_DOMAIN = "domain";
    public static final String K_HOST_KEY_POLICY = "hostKeyPolicy";
    public static final String K_PASSIVE = "passive";
    public static final String K_FTPS = "ftps";

    /** Every type, as {@code token, label, whether it needs the network}. */
    public static List<String[]> catalogue() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {KEEP, "Keep on this engine (download from here)", "false"});
        out.add(new String[] {LOCAL, "Directory or network path", "false"});
        out.add(new String[] {S3, "Amazon S3 or S3-compatible storage", "true"});
        out.add(new String[] {AZURE, "Azure Blob Storage", "true"});
        out.add(new String[] {SFTP, "SFTP", "true"});
        out.add(new String[] {FTP, "FTP or FTPS", "true"});
        return out;
    }

    /**
     * Builds the destination described by {@code config}.
     *
     * <p>{@code staging} is the plugin's own directory under {@code appdata}, used by
     * {@link #KEEP} and as scratch space by the network destinations.
     *
     * @throws IllegalArgumentException when a required setting is missing or unusable,
     *                                  with a message naming the field
     */
    public static Destination open(DestinationConfig config, Path staging) throws IOException {
        String type = config == null ? "" : config.type();
        switch (type) {
            case KEEP:
            case "":
                return new LocalDestination(staging, "kept on this engine");
            case LOCAL: {
                String path = config.require(K_PATH, "The directory or network path");
                // The path decides. A share reached over SMB carries its own credentials;
                // a directory the platform has already mounted does not need any, and
                // offering it a username would imply this plugin could use one.
                return SmbDestination.looksLikeShare(path)
                    ? new SmbDestination(config)
                    : new LocalDestination(Path.of(path), null);
            }
            case S3:
                return new S3Destination(config);
            case AZURE:
                return new AzureBlobDestination(config);
            case SFTP:
                return new SftpDestination(config);
            case FTP:
                return new FtpDestination(config);
            default:
                throw new IllegalArgumentException("unknown destination type '" + type + "'");
        }
    }

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    /**
     * The shape of every archive this plugin writes.
     *
     * <pre>
     *   oie-backup-20260919T020000Z.zip
     *   oie-backup-20260919T020000Z-nightly.zip.enc
     * </pre>
     *
     * <p>The timestamp is UTC and sorts lexically, which is what lets retention pick the
     * oldest without asking a destination for modification times it may not keep.
     */
    private static final Pattern ARCHIVE_NAME = Pattern.compile(
        "^oie-backup-\\d{8}T\\d{6}Z(-[A-Za-z0-9._-]{1,48})?\\.zip(\\.enc)?$");

    /**
     * Whether a file name is one this plugin wrote.
     *
     * <p>Used everywhere a listing is read, and it is the reason retention is safe to point
     * at a directory that holds other things: a file this pattern does not match is never
     * listed, never counted and therefore never deleted. Pointing a backup at
     * {@code /mnt/archive} that already holds a year of invoices is a configuration mistake,
     * but it must not become a data loss incident.
     */
    public static boolean isArchiveName(String name) {
        return name != null && ARCHIVE_NAME.matcher(name).matches();
    }

    /**
     * Refuses anything that is not a bare file name.
     *
     * <p>Names reach the destinations from three places: this plugin's own generator, a
     * listing read back from storage, and the console. Only the first is trusted, so the
     * check lives at the boundary rather than at the caller -- a slash, a backslash, a
     * {@code ..} segment, a leading tilde or an empty string is refused before any
     * implementation joins it to a directory, a bucket prefix or a remote path.
     */
    public static String requireSimpleName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a file name is required");
        }
        String n = name.trim();
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.startsWith("~")
            || n.contains("..") || n.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("'" + n + "' is not a plain file name");
        }
        return n;
    }

    /**
     * Trims a prefix or remote directory to a usable form: no leading slash, no trailing
     * slash, no traversal. Empty means the root of the bucket, container or login directory.
     */
    public static String normalisePrefix(String value) {
        if (value == null) {
            return "";
        }
        String t = value.trim().replace('\\', '/');
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.contains("..")) {
            throw new IllegalArgumentException("the prefix must not contain '..'");
        }
        return t;
    }

    /** {@code prefix/name}, or {@code name} when there is no prefix. */
    public static String join(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /**
     * The file name part of a key, or null when the key is not directly inside the prefix.
     *
     * <p>Returning null for a deeper key is the point: a bucket shared with something else
     * that happens to have {@code backups/2025/old.zip} under the configured prefix must not
     * have that file appear in a listing whose next use might be retention.
     */
    public static String nameUnder(String prefix, String key) {
        if (key == null) {
            return null;
        }
        String rest;
        if (prefix == null || prefix.isEmpty()) {
            rest = key;
        } else if (key.startsWith(prefix + "/")) {
            rest = key.substring(prefix.length() + 1);
        } else {
            return null;
        }
        return rest.indexOf('/') >= 0 ? null : rest;
    }

    /** A human-readable byte count, for the console and the log. */
    public static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = n;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, units[i]);
    }
}
