/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.openintegrationengine.plugins.backup.destination.DestinationConfig;
import org.openintegrationengine.plugins.backup.destination.Destinations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What gets backed up, when, where to, and for how long it is kept.
 *
 * <p>Stored through {@code ConfigurationController.saveProperty}, which writes to the
 * {@code configuration} table. Two things follow from that and both matter here. The
 * settings live in the database, so they survive this image's rebuild of {@code conf/} and
 * {@code extensions/} on every boot -- a backup schedule that a container restart forgot
 * would be worse than no schedule, because nobody would notice. And they are shared by
 * every node against the same database, which is what lets three engines in a cluster take
 * one backup between them rather than three.
 *
 * <h2>Secrets</h2>
 *
 * <p>An S3 secret key, an Azure account key, an SFTP password or private key and the
 * archive passphrase are each encrypted with the engine's own {@code Encryptor} before
 * being stored, so a database dump does not hand over the backup store along with it. None
 * is ever returned by the API: the console is told whether a secret is set, never what it
 * is, and a settings save that does not resupply one keeps the one already there. That is
 * what makes it safe to edit the bucket name without having to paste the key back in.
 *
 * <h2>Why every destination's settings are kept, not just the current one's</h2>
 *
 * <p>Settings are namespaced by destination type -- {@code dest.s3.bucket},
 * {@code dest.sftp.host} -- so switching the destination to try SFTP and switching back
 * does not lose the S3 configuration. It costs a prefix and saves the support conversation
 * that starts "I only changed it to test something".
 */
public final class BackupSettings {

    /** Property group; matches the plugin point name so it groups with the extension. */
    static final String GROUP = "Backup";

    private static final String K_ENABLED = "enabled";
    private static final String K_LABEL = "label";

    private static final String K_SCHEDULE_KIND = "schedule.kind";
    private static final String K_SCHEDULE_INTERVAL = "schedule.intervalMinutes";
    private static final String K_SCHEDULE_TIME = "schedule.timeOfDay";
    private static final String K_SCHEDULE_DOW = "schedule.dayOfWeek";
    private static final String K_SCHEDULE_DOM = "schedule.dayOfMonth";
    private static final String K_SCHEDULE_CRON = "schedule.cron";
    private static final String K_SCHEDULE_ZONE = "schedule.zone";

    private static final String K_INCLUDE_CONFIG = "config.included";
    private static final String K_CONFIG_SCOPE = "config.scope";

    private static final String K_INCLUDE_MESSAGES = "messages.included";
    private static final String K_MESSAGE_CHANNELS = "messages.channels";
    private static final String K_MESSAGE_DAYS = "messages.days";
    private static final String K_MESSAGE_STATUSES = "messages.statuses";
    private static final String K_MESSAGE_ATTACHMENTS = "messages.attachments";

    private static final String K_DESTINATION = "destination";
    private static final String DEST_PREFIX = "dest.";

    private static final String K_ENCRYPT = "encrypt";
    private static final String K_PASSPHRASE = "passphrase";

    private static final String K_KEEP_COUNT = "retention.keepCount";
    private static final String K_KEEP_DAYS = "retention.keepDays";
    private static final String K_PRUNE = "retention.prune";

    /**
     * The settings that are encrypted before storage and never returned by the API.
     *
     * <p>Held as a set of unprefixed key names rather than as a naming convention, because
     * "anything called secret" would silently fail to protect a field somebody later adds
     * under a different name -- and the failure would be invisible until the day the
     * database was dumped.
     */
    private static final Set<String> SECRET_KEYS = Set.of(
        Destinations.K_SECRET, Destinations.K_SAS, Destinations.K_PRIVATE_KEY,
        Destinations.K_PASSPHRASE);

    /**
     * Posted in place of a secret to clear it.
     *
     * <p>An empty string cannot mean "clear", because an empty string is also what the
     * console sends for a field the operator did not touch. Something has to distinguish
     * the two, and a sentinel nobody would type is the cheapest thing that does.
     *
     * <p>Plain ASCII, deliberately. The obvious choice would be a control character, which
     * nobody could type by accident -- and which XML cannot carry, so it would arrive
     * mangled or as a 500 from the engine's deserialiser. A long, ugly, printable string is
     * the version that survives the transport.
     */
    public static final String CLEAR_SECRET = "--oie-backup-clear-this-secret--";

    private final Map<String, String> values = new LinkedHashMap<>();

    private BackupSettings() {
    }

    // ------------------------------------------------------------------
    // Load and save
    // ------------------------------------------------------------------

    public static BackupSettings load() {
        ConfigurationController config = config();
        Properties stored = config.getPropertiesForGroup(GROUP);
        BackupSettings settings = new BackupSettings();
        if (stored != null) {
            for (String key : stored.stringPropertyNames()) {
                String value = stored.getProperty(key);
                if (isSecretKey(key) && value != null && !value.isEmpty()) {
                    try {
                        value = config.getEncryptor().decrypt(value);
                    } catch (Exception e) {
                        /*
                         * A credential that will not decrypt is treated as absent rather
                         * than fatal. The keystore may have been replaced -- which is
                         * exactly what happens when an engine is rebuilt from a backup --
                         * and the settings page has to load so the operator can re-enter it.
                         */
                        value = "";
                    }
                }
                settings.values.put(key, value == null ? "" : value);
            }
        }
        return settings;
    }

    /**
     * Writes the supplied keys back, leaving everything else as it was.
     *
     * <p>A partial save rather than a whole-object one, because the console edits one
     * section at a time and a whole-object save would mean the destination form having to
     * post the schedule back untouched -- which is how a schedule gets quietly reset by a
     * page that had a stale copy of it.
     */
    public void save(Map<String, String> incoming) {
        ConfigurationController config = config();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();

            if (isSecretKey(key)) {
                if (CLEAR_SECRET.equals(value)) {
                    config.saveProperty(GROUP, key, "");
                    values.remove(key);
                } else if (!value.isEmpty()) {
                    config.saveProperty(GROUP, key, config.getEncryptor().encrypt(value));
                    values.put(key, value);
                }
                // An empty value for a secret means "not supplied", so the stored one stays.
                continue;
            }

            config.saveProperty(GROUP, key, value);
            values.put(key, value);
        }
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        if (K_PASSPHRASE.equals(key)) {
            return true;
        }
        int lastDot = key.lastIndexOf('.');
        String tail = lastDot < 0 ? key : key.substring(lastDot + 1);
        return key.startsWith(DEST_PREFIX) && SECRET_KEYS.contains(tail);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private String raw(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    private boolean bool(String key, boolean fallback) {
        String v = get(key, null);
        return v == null ? fallback : "true".equalsIgnoreCase(v);
    }

    private int integer(String key, int fallback) {
        try {
            String v = get(key, null);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Whether the schedule is allowed to run. Manual backups ignore this. */
    public boolean enabled() {
        return bool(K_ENABLED, false);
    }

    /**
     * An optional suffix for archive names, so two schedules or two environments writing to
     * one bucket stay distinguishable.
     */
    public String label() {
        String label = get(K_LABEL, "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < label.length() && sb.length() < 48; i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    public Schedule schedule() {
        try {
            return Schedule.of(get(K_SCHEDULE_KIND, "off"),
                get(K_SCHEDULE_INTERVAL, "1440"),
                get(K_SCHEDULE_TIME, "02:00"),
                get(K_SCHEDULE_DOW, "1"),
                get(K_SCHEDULE_DOM, "1"),
                get(K_SCHEDULE_CRON, ""),
                get(K_SCHEDULE_ZONE, ""));
        } catch (IllegalArgumentException e) {
            /*
             * A stored schedule that will not parse means no scheduled backups, not a
             * plugin that fails to start. It can only get here by being edited in the
             * database directly, or by a downgrade, and validate() reports it on the page.
             */
            return Schedule.off();
        }
    }

    public boolean includeConfig() {
        return bool(K_INCLUDE_CONFIG, true);
    }

    public Set<BackupScope> configScope() {
        return BackupScope.parse(get(K_CONFIG_SCOPE, ""));
    }

    public boolean includeMessages() {
        return bool(K_INCLUDE_MESSAGES, false);
    }

    public MessageCapture.Selection messageSelection() {
        Set<String> channels = new LinkedHashSet<>();
        for (String part : get(K_MESSAGE_CHANNELS, "").split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                channels.add(t);
            }
        }
        return new MessageCapture.Selection(channels,
            integer(K_MESSAGE_DAYS, 0),
            MessageCapture.Selection.parseStatuses(get(K_MESSAGE_STATUSES, "")),
            bool(K_MESSAGE_ATTACHMENTS, true));
    }

    public String destinationType() {
        return get(K_DESTINATION, Destinations.KEEP).toLowerCase(Locale.ROOT);
    }

    /** The settings for the current destination, with its type's prefix stripped. */
    public DestinationConfig destination() {
        return destination(destinationType());
    }

    public DestinationConfig destination(String type) {
        String prefix = DEST_PREFIX + type + ".";
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), value);
            }
        });
        return new DestinationConfig(type, out);
    }

    public boolean encryptArchives() {
        return bool(K_ENCRYPT, false);
    }

    /**
     * The archive passphrase, decrypted.
     *
     * <p>Returned as a char array and cleared by the caller, which is the convention
     * everything in the JDK's crypto API expects. It is a gesture rather than a guarantee
     * -- the value was a String on its way out of the configuration table -- but it keeps
     * the passphrase out of the one place it would otherwise certainly end up, which is a
     * heap dump taken while a backup is running.
     */
    public char[] passphrase() {
        String v = raw(K_PASSPHRASE);
        return v.isEmpty() ? new char[0] : v.toCharArray();
    }

    public boolean hasPassphrase() {
        return !raw(K_PASSPHRASE).isEmpty();
    }

    /** How many archives to keep. 0 means keep everything. */
    public int keepCount() {
        return Math.max(0, integer(K_KEEP_COUNT, 0));
    }

    /** How many days to keep an archive for. 0 means keep everything. */
    public int keepDays() {
        return Math.max(0, integer(K_KEEP_DAYS, 0));
    }

    /**
     * Whether retention may actually delete anything.
     *
     * <p>Separate from the two limits, and off by default, so that setting "keep 7" is not
     * by itself an instruction to delete. Deleting a backup is irreversible and is somebody
     * else's data-retention policy; this plugin will carry it out, and it will not decide
     * it. The console shows exactly which archives the current limits would remove before
     * the switch is turned on, so the decision is made against a list rather than against an
     * idea of one.
     */
    public boolean pruneEnabled() {
        return bool(K_PRUNE, false);
    }

    /** Whether a secret is set, without revealing it. This is all the API returns. */
    public boolean hasSecret(String type, String key) {
        return !raw(DEST_PREFIX + type + "." + key).isEmpty();
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Everything wrong with the current settings, in sentences.
     *
     * <p>Reported rather than thrown, and reported for all of them at once: a settings page
     * that surfaces one problem per save is a page somebody has to visit five times.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();

        if (!includeConfig() && !includeMessages()) {
            problems.add("Nothing is selected to back up. Choose configuration, messages, "
                + "or both.");
        }
        if (includeConfig() && configScope().isEmpty()) {
            problems.add("Configuration is selected, but no parts of it are.");
        }

        String kind = get(K_SCHEDULE_KIND, "off");
        if ("cron".equalsIgnoreCase(kind)) {
            try {
                Cron cron = Cron.parse(get(K_SCHEDULE_CRON, ""));
                if (cron.nextAfter(java.time.LocalDateTime.now()) == null) {
                    problems.add("The cron expression is valid but never fires -- check the "
                        + "day of month against the month.");
                }
            } catch (IllegalArgumentException e) {
                problems.add("The cron expression is not usable: " + e.getMessage());
            }
        }
        try {
            String zone = get(K_SCHEDULE_ZONE, "");
            if (!zone.isEmpty()) {
                java.time.ZoneId.of(zone);
            }
        } catch (RuntimeException e) {
            problems.add("'" + get(K_SCHEDULE_ZONE, "") + "' is not a time zone the JVM "
                + "knows. Use a region name such as Australia/Melbourne, or UTC.");
        }

        if (encryptArchives() && !hasPassphrase()) {
            problems.add("Archive encryption is on, but no passphrase is set. Nothing would "
                + "be written.");
        }

        String type = destinationType();
        DestinationConfig dest = destination(type);
        switch (type) {
            case Destinations.LOCAL: {
                String path = dest.get(Destinations.K_PATH, null);
                if (path == null) {
                    problems.add("The destination directory or network path is not set.");
                    break;
                }
                boolean share = path.startsWith("\\\\") || path.startsWith("//")
                    || path.toLowerCase(Locale.ROOT).startsWith("smb://");
                boolean hasUser = dest.get(Destinations.K_USERNAME, null) != null;
                boolean hasPassword = dest.get(Destinations.K_SECRET, null) != null;
                if (!share && (hasUser || hasPassword)) {
                    /*
                     * Named rather than ignored. Credentials on a plain directory do
                     * nothing -- the path is reached as the engine's own process user --
                     * and somebody who filled them in believes the opposite, which is a
                     * belief worth correcting before the first scheduled run rather than
                     * after it.
                     */
                    problems.add("Credentials are set, but '" + path + "' is an ordinary "
                        + "directory rather than a network share, so they are not used. "
                        + "For a share, give the path as \\\\server\\share\\folder or "
                        + "smb://server/share/folder.");
                }
                if (share && hasPassword && !hasUser) {
                    problems.add("A password is set for the network path but no username "
                        + "is, which would connect anonymously and ignore the password.");
                }
                break;
            }
            case Destinations.S3:
                requireAll(problems, dest, "S3",
                    Destinations.K_BUCKET, "the bucket",
                    Destinations.K_ACCESS_KEY, "the access key id",
                    Destinations.K_SECRET, "the secret access key");
                break;
            case Destinations.AZURE:
                requireAll(problems, dest, "Azure",
                    Destinations.K_ACCOUNT, "the storage account",
                    Destinations.K_CONTAINER, "the container");
                if ("sas".equalsIgnoreCase(dest.get(Destinations.K_AZURE_AUTH, "key"))) {
                    if (dest.get(Destinations.K_SAS, null) == null) {
                        problems.add("Azure: the SAS token is not set.");
                    }
                } else if (dest.get(Destinations.K_SECRET, null) == null) {
                    problems.add("Azure: the account key is not set.");
                }
                break;
            case Destinations.SFTP:
                requireAll(problems, dest, "SFTP",
                    Destinations.K_HOST, "the host",
                    Destinations.K_USERNAME, "the username");
                if ("key".equalsIgnoreCase(dest.get(Destinations.K_AUTH_METHOD, "password"))) {
                    if (dest.get(Destinations.K_PRIVATE_KEY, null) == null) {
                        problems.add("SFTP: key authentication is selected but no private "
                            + "key is set.");
                    }
                } else if (dest.get(Destinations.K_SECRET, null) == null) {
                    problems.add("SFTP: the password is not set.");
                }
                if (!"trust_any".equalsIgnoreCase(
                        dest.get(Destinations.K_HOST_KEY_POLICY, "known_hosts"))
                    && dest.get(Destinations.K_KNOWN_HOSTS, null) == null) {
                    problems.add("SFTP: no known_hosts lines are set, and the host key "
                        + "policy is not 'trust any'. Run ssh-keyscan against the server "
                        + "and paste its output.");
                }
                break;
            case Destinations.FTP:
                requireAll(problems, dest, "FTP",
                    Destinations.K_HOST, "the host",
                    Destinations.K_USERNAME, "the username",
                    Destinations.K_SECRET, "the password");
                break;
            default:
                break;
        }

        if (pruneEnabled() && keepCount() == 0 && keepDays() == 0) {
            problems.add("Retention is switched on but neither limit is set, so nothing "
                + "would ever be removed.");
        }
        return problems;
    }

    private static void requireAll(List<String> problems, DestinationConfig dest,
                                   String label, String... keysAndLabels) {
        for (int i = 0; i + 1 < keysAndLabels.length; i += 2) {
            if (dest.get(keysAndLabels[i], null) == null) {
                problems.add(label + ": " + keysAndLabels[i + 1] + " is not set.");
            }
        }
    }

    // ------------------------------------------------------------------
    // For the console
    // ------------------------------------------------------------------

    /**
     * Every setting, as {@code key \t value} rows, with secrets replaced by whether they
     * are set.
     *
     * <p>One method rather than a field-by-field response object, for the reason
     * {@link DestinationConfig} is a map: six destinations' worth of fields would otherwise
     * be six response shapes for the console to know about. The secret substitution happens
     * here, once, on the way out -- which is the only place it can be guaranteed.
     */
    public List<String> rows() {
        List<String> out = new ArrayList<>();
        values.forEach((key, value) -> {
            /*
             * The history and the run state share this property group -- one group per
             * extension is what the configuration table offers -- but they are not
             * settings, and the status response returns them separately and already
             * parsed. Emitting them here as well would send every history row twice, and
             * the second copy with its tabs flattened by the row encoding, which is a
             * confusing thing to find while reading the API.
             */
            if (key.startsWith("history.") || key.startsWith("state.")) {
                return;
            }
            if (isSecretKey(key)) {
                out.add(Tsv.join(key, value == null || value.isEmpty() ? "" : "set"));
            } else {
                out.add(Tsv.join(key, escapeLines(value)));
            }
        });
        // Defaults the stored set does not carry yet, so the form has something to draw.
        if (!values.containsKey(K_DESTINATION)) {
            out.add(Tsv.join(K_DESTINATION, Destinations.KEEP));
        }
        if (!values.containsKey(K_SCHEDULE_KIND)) {
            out.add(Tsv.join(K_SCHEDULE_KIND, "off"));
        }
        if (!values.containsKey(K_SCHEDULE_TIME)) {
            out.add(Tsv.join(K_SCHEDULE_TIME, "02:00"));
        }
        if (!values.containsKey(K_SCHEDULE_ZONE)) {
            out.add(Tsv.join(K_SCHEDULE_ZONE, java.time.ZoneId.systemDefault().getId()));
        }
        if (!values.containsKey(K_CONFIG_SCOPE)) {
            out.add(Tsv.join(K_CONFIG_SCOPE, BackupScope.format(BackupScope.defaults())));
        }
        return out;
    }

    /**
     * Escapes newlines on the way to the console.
     *
     * <p>Two settings are genuinely multi-line -- an SFTP private key and its
     * {@code known_hosts} lines -- and the row encoding strips control characters, which
     * would flatten both into one unusable line and then save that flattened version back
     * the next time the form was submitted. Escaping them here and unescaping in the page
     * keeps a key that goes out the same as the key that comes back.
     */
    private static String escapeLines(String value) {
        return value == null ? ""
            : value.replace("\\", "\\\\").replace("\r\n", "\\n")
                .replace("\n", "\\n").replace("\r", "\\n");
    }
}
