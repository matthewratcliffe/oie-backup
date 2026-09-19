/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

/**
 * Tab-separated field encoding for every record this plugin stores and every row it sends
 * to the console.
 *
 * <p>The same choice, for the same reasons, as the other extensions in this repository:
 * XStream renders an immutable list as an undecodable {@code CollSer} blob and a list of
 * maps in a shape the console's decoder cannot reliably unpick, so a flat list of strings
 * is the only structure that survives the round trip. Control characters are stripped
 * rather than escaped, because one stray tab inside a value would shift every later field
 * of the record by one -- silently, and permanently once the record is read back in its
 * corrupted form.
 */
final class Tsv {

    private Tsv() {
    }

    static String join(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(clean(fields[i]));
        }
        return sb.toString();
    }

    /**
     * Splits into exactly {@code count} fields, padding with empty strings.
     *
     * <p>Padding rather than failing: a history row written by an older version of this
     * plugin has fewer fields, and it is read back after an upgrade -- which is precisely
     * the moment somebody is looking at this plugin.
     */
    static String[] split(String line, int count) {
        String[] parts = line == null ? new String[0] : line.split("\t", -1);
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = i < parts.length ? parts[i] : "";
        }
        return out;
    }

    static String clean(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < ' ' || c == '\u007f' ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
