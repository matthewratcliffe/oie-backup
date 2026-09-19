/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What is inside an archive, written into the archive.
 *
 * <p>An archive that cannot say what it contains is an archive somebody has to open and
 * read to find out, and the moment that matters is the one where an engine is down and
 * there are six of them to choose between. So {@code manifest.properties} sits at the top
 * of every archive and carries the whole answer: when it was taken, from which server and
 * which environment, on which engine version, which configuration scopes are in it and how
 * many objects each one held, whether messages are included and for which channels, and
 * what the restore will not be able to do.
 *
 * <p>Plain properties, rendered by hand. Not JSON, because the file is read by people with
 * {@code unzip -p} at least as often as by this plugin, and not
 * {@link java.util.Properties#store} because that writes a timestamp comment -- which would
 * make two manifests with identical content differ, and the first thing anybody does with
 * two backups is diff them.
 *
 * <p>A manifest read out of an archive is <b>untrusted input</b>. It has been on somebody
 * else's storage, and an archive can be assembled by hand. Nothing here is used to decide
 * where a file goes or what gets written: the restore reads its file list from the zip's
 * own entries, which are validated separately, and treats the manifest as a description to
 * show and to check against, never as an instruction.
 */
final class Manifest {

    /** Bumped when a change would stop an older plugin reading a newer archive correctly. */
    static final int FORMAT_VERSION = 1;

    static final String FILE_NAME = "manifest.properties";

    static final String K_FORMAT = "formatVersion";
    static final String K_PLUGIN_VERSION = "pluginVersion";
    static final String K_CREATED_AT = "createdAt";
    static final String K_TRIGGER = "trigger";
    static final String K_LABEL = "label";
    static final String K_SERVER_ID = "server.id";
    static final String K_SERVER_NAME = "server.name";
    static final String K_ENVIRONMENT = "server.environment";
    static final String K_ENGINE_VERSION = "server.engineVersion";
    static final String K_CONFIG_SCOPES = "config.scopes";
    static final String K_CONFIG_COUNT = "config.count.";
    static final String K_MESSAGES = "messages.included";
    static final String K_MESSAGE_CHANNELS = "messages.channelCount";
    static final String K_MESSAGE_TOTAL = "messages.total";
    static final String K_MESSAGE_FROM = "messages.from";
    static final String K_MESSAGE_TO = "messages.to";
    static final String K_MESSAGE_STATUSES = "messages.statuses";
    static final String K_MESSAGE_ATTACHMENTS = "messages.attachments";
    static final String K_MESSAGE_CHANNEL = "messages.channel.";
    static final String K_ENCRYPTED = "encrypted";
    static final String K_PROBLEMS = "problems";

    private final Map<String, String> values = new LinkedHashMap<>();

    Manifest() {
        values.put(K_FORMAT, Integer.toString(FORMAT_VERSION));
    }

    Manifest put(String key, Object value) {
        values.put(key, value == null ? "" : String.valueOf(value));
        return this;
    }

    String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean getBool(String key) {
        return "true".equalsIgnoreCase(get(key, "false"));
    }

    Instant createdAt() {
        try {
            return Instant.parse(get(K_CREATED_AT, ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The configuration scopes this archive holds, as tokens. */
    Set<BackupScope> scopes() {
        String csv = get(K_CONFIG_SCOPES, "");
        // Not BackupScope.parse: an empty list here means the archive holds no
        // configuration at all, and parse() would helpfully turn that into "everything".
        Set<BackupScope> out = new java.util.LinkedHashSet<>();
        if (csv.isBlank()) {
            return out;
        }
        for (BackupScope scope : BackupScope.values()) {
            for (String token : csv.split(",")) {
                if (scope.token().equals(token.trim())) {
                    out.add(scope);
                }
            }
        }
        return out;
    }

    /** The channels whose messages are in the archive: {@code id \t name \t count}. */
    List<String> messageChannels() {
        List<String> out = new ArrayList<>();
        // Sorted by key so the order is the order they were written, not a hash order.
        new TreeMap<>(values).forEach((key, value) -> {
            if (key.startsWith(K_MESSAGE_CHANNEL)) {
                out.add(value);
            }
        });
        return out;
    }

    /** Counts per configuration scope, for the archive's description in the console. */
    List<String> counts() {
        List<String> out = new ArrayList<>();
        for (BackupScope scope : BackupScope.values()) {
            String v = values.get(K_CONFIG_COUNT + scope.token());
            if (v != null && !v.isBlank()) {
                out.add(Tsv.join(scope.token(), scope.label(), v));
            }
        }
        return out;
    }

    /** Every entry, as {@code key \t value}, for the console's raw view of an archive. */
    List<String> rows() {
        List<String> out = new ArrayList<>();
        values.forEach((k, v) -> out.add(Tsv.join(k, v)));
        return out;
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Open Integration Engine backup archive.\n");
        sb.append("# Written by the Backup extension. Keys are stable across versions;\n");
        sb.append("# unknown keys are ignored on restore.\n\n");
        values.forEach((k, v) -> sb.append(escape(k)).append(" = ")
            .append(v == null ? "" : escape(v)).append('\n'));
        return sb.toString();
    }

    static Manifest parse(String text) {
        Manifest manifest = new Manifest();
        if (text == null) {
            return manifest;
        }
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            manifest.values.put(unescape(line.substring(0, eq).trim()),
                unescape(line.substring(eq + 1).trim()));
        }
        return manifest;
    }

    /**
     * A sentence describing an archive, for a list where every row has to be readable at a
     * glance.
     */
    String summary() {
        StringBuilder sb = new StringBuilder();
        int scopes = scopes().size();
        sb.append(scopes).append(scopes == 1 ? " configuration part" : " configuration parts");
        if (getBool(K_MESSAGES)) {
            long total = 0;
            try {
                total = Long.parseLong(get(K_MESSAGE_TOTAL, "0"));
            } catch (NumberFormatException ignored) {
                // An older archive without the total still describes its channels.
            }
            sb.append(", ").append(total).append(" message").append(total == 1 ? "" : "s")
              .append(" from ").append(getInt(K_MESSAGE_CHANNELS, 0)).append(" channel(s)");
        } else {
            sb.append(", no messages");
        }
        String env = get(K_ENVIRONMENT, "");
        String server = get(K_SERVER_NAME, "");
        if (!server.isBlank() || !env.isBlank()) {
            sb.append(" \u2014 ").append(env.isBlank() ? server
                : (server.isBlank() ? env : env + " / " + server));
        }
        return sb.toString();
    }

    // Properties escaping, restricted to what a manifest value can contain: a newline in a
    // channel name would otherwise end the record early and shift everything after it.
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reads the manifest out of an already-open archive, or an empty one when absent. */
    static Manifest from(Archive archive) throws IOException {
        String text = archive.readText(FILE_NAME);
        return text == null ? new Manifest() : parse(text);
    }
}
