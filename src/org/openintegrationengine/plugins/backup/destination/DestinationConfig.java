/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The settings one destination was configured with, as flat strings.
 *
 * <p>A map rather than a class per destination with its own fields. Six destinations with
 * between four and nine settings each would otherwise be six parallel sets of getters,
 * setters, defaults and persistence, all of which the settings form, the configuration
 * table and the console would have to know about separately. Keeping them as named strings
 * means adding a seventh destination touches its own class, one factory branch and the
 * form -- not the storage layer.
 *
 * <p>The cost is that a typo in a key is a default rather than a compile error, which is
 * why every key this package reads is a constant declared in {@link Destinations} and
 * nothing reads a literal.
 *
 * <p>Secrets live in here alongside everything else, decrypted, and must not be logged.
 * {@link #toString()} is overridden to make that harder to do by accident.
 */
public final class DestinationConfig {

    private final String type;
    private final Map<String, String> values;

    public DestinationConfig(String type, Map<String, String> values) {
        this.type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        Map<String, String> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((k, v) -> copy.put(k, v == null ? "" : v));
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    public String type() {
        return type;
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** Like {@link #get}, but keeps leading and trailing whitespace. For keys and bodies. */
    public String raw(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    public int getInt(String key, int fallback) {
        try {
            String v = get(key, null);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String v = get(key, null);
        if (v == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** Throws with the field's name when a required setting is missing. */
    public String require(String key, String label) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return v;
    }

    @Override
    public String toString() {
        // Never the values. This object holds an S3 secret key and an SFTP private key.
        return "DestinationConfig[" + type + ", " + values.size() + " settings]";
    }
}
