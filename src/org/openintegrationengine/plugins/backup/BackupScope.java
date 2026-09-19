/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The parts of engine configuration a backup can hold, chosen one by one.
 *
 * <p>Two things are being separated here and it is worth being explicit about which is
 * which. {@link #defaults()} is what a backup takes when nobody has said otherwise, and it
 * is <b>everything</b> -- a backup exists to be restored from after something has gone
 * wrong, and a backup that quietly left out the alerts is found to have left them out on
 * the day it is needed. The per-scope choice is there for the other direction: taking a
 * configuration-only snapshot before a deployment, or moving one instance's channels into
 * another environment without dragging that instance's own server name and SMTP host along
 * with them.
 *
 * <p>That is the opposite default from the Git Sync extension in this repository, which
 * syncs four scopes unless told otherwise. The reason they differ is the reason they
 * exist: a repository is a shared desired state and copying one environment's identity into
 * it would be wrong, whereas a backup is this engine's own record of itself and an omission
 * is a gap.
 *
 * <p>The file layout each scope writes is <b>identical</b> to the one Git Sync and
 * {@code scripts/oie-config-pull.sh} use, so the {@code config/} directory inside an
 * archive can be dropped into a configuration repository unchanged, and a checkout can be
 * restored by this plugin. That is not a coincidence to preserve by accident -- it is the
 * reason a backup here is a zip of files rather than one opaque XML document.
 */
public enum BackupScope {

    CHANNELS("channels", "Channels", true),
    CODE_TEMPLATES("code-templates", "Code templates", true),
    CHANNEL_GROUPS("channel-groups", "Channel groups", true),
    CONFIGURATION_MAP("configuration-map", "Configuration map", true),
    ALERTS("alerts", "Alerts", true),
    GLOBAL_SCRIPTS("global-scripts", "Global scripts", true),
    /** Settings &gt; Server: server name, environment name, SMTP, ports, metadata columns. */
    SERVER_SETTINGS("server-settings", "Settings: Server", false),
    /** Settings &gt; Administrator: update and notification preferences. */
    ADMINISTRATOR_SETTINGS("administrator-settings", "Settings: Administrator", false),
    CHANNEL_TAGS("channel-tags", "Settings: Tags", true),
    RESOURCES("resources", "Settings: Resources", false),
    /** Settings &gt; Data Pruner, held as that extension's plugin properties. */
    DATA_PRUNER("data-pruner", "Settings: Data Pruner", false),
    /** Volume Monitor rules and settings, this repository's monitoring extension. */
    VOLUME_MONITOR("volume-monitor", "Volume Monitor", false),
    /**
     * User accounts, <b>without</b> passwords.
     *
     * <p>The engine keeps credentials in a separate table behind a one-way digest and
     * exposes no way to write them back, so an account restored from a backup exists,
     * carries its role, and cannot be logged into until somebody sets a password. Backing
     * the accounts up is still worth doing -- rebuilding twenty users and their roles by
     * hand is its own outage -- but it must not be mistaken for restoring access, and the
     * restore report says so every time rather than only in this comment.
     */
    USERS("users", "User accounts (no passwords)", false);

    private final String token;
    private final String label;
    /**
     * Whether this scope describes work rather than this instance's own identity.
     *
     * <p>Only used to mark scopes in the console, so somebody copying a backup into another
     * environment can see at a glance which parts would overwrite that environment's own
     * name, mail server or pruning schedule. Every scope is still backed up by default.
     */
    private final boolean portable;

    BackupScope(String token, String label, boolean portable) {
        this.token = token;
        this.label = label;
        this.portable = portable;
    }

    public String token() {
        return token;
    }

    public String label() {
        return label;
    }

    public boolean portable() {
        return portable;
    }

    /** Everything. See the class comment for why a backup's default is not Git Sync's. */
    public static Set<BackupScope> defaults() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(values())));
    }

    /** The scopes that do not carry an instance's own identity. */
    public static Set<BackupScope> portableOnly() {
        Set<BackupScope> out = new LinkedHashSet<>();
        for (BackupScope s : values()) {
            if (s.portable) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Parses a comma-separated list of tokens.
     *
     * <p>An empty or unparseable list means {@link #defaults()}, and an unrecognised token
     * is dropped rather than failing the whole list. Both choices err towards backing up
     * more than was asked for, which is the harmless direction: the expensive mistake here
     * is a backup that silently contains less than the operator believes.
     */
    public static Set<BackupScope> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return defaults();
        }
        Set<BackupScope> out = parseStrict(csv);
        return out.isEmpty() ? defaults() : out;
    }

    /**
     * Parses a list and takes it literally: no list means no scopes.
     *
     * <p>The difference from {@link #parse} is the whole point, and it is the difference
     * between the two directions. Backing up more than was asked for is harmless, so
     * {@code parse} treats an empty or unrecognisable list as "everything" -- the expensive
     * mistake there is a backup that silently contains less than the operator believes.
     *
     * <p>Restoring is the opposite. "Everything" overwrites this engine's channels, its
     * alerts, its server settings and its user accounts, so an empty list must mean nothing
     * at all. Falling back to a default here would turn "I unticked every box" into "apply
     * all thirteen", which is the single worst thing this extension could do while claiming
     * to have honoured a selection.
     *
     * <p>An unrecognised token is dropped rather than failing the whole list, so an archive
     * from a newer version naming a scope this one does not have still restores the twelve
     * it does understand.
     */
    public static Set<BackupScope> parseStrict(String csv) {
        Set<BackupScope> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            for (BackupScope s : values()) {
                if (s.token.equals(t) || s.name().toLowerCase(Locale.ROOT).equals(t)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Renders a set back to the stored form. Ordered by declaration, so it is stable. */
    public static String format(Set<BackupScope> scopes) {
        StringBuilder sb = new StringBuilder();
        for (BackupScope s : values()) {
            if (scopes != null && scopes.contains(s)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s.token);
            }
        }
        return sb.toString();
    }

    /** One row per scope for the console: token, label, portable. */
    public static java.util.List<String> rows() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (BackupScope s : values()) {
            out.add(Tsv.join(s.token, s.label, s.portable));
        }
        return out;
    }
}
