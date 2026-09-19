/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What has happened: when the last backup ran, whether it worked, and the history.
 *
 * <p>In the {@code configuration} table beside the settings, which makes it survive this
 * image's rebuild of {@code conf/} on every boot and -- more importantly -- makes it
 * <b>shared</b>. Every node against the same database reads and writes the same record,
 * and that sharing is the whole of this plugin's cluster story.
 *
 * <h2>Three engines, one backup</h2>
 *
 * <p>There is no leader election and no per-node configuration. A node that wakes up and
 * finds a backup owed claims it by writing {@code state.runningOn} and {@code state.lastRun}
 * <i>before</i> starting, so a neighbour waking a second later finds the work taken and
 * goes back to sleep. The claim is a courtesy rather than a lock -- two nodes that read at
 * the same instant can both claim -- and that is an accepted trade: the cost of losing the
 * race is one extra archive in the destination with a timestamp a second apart, and the
 * cost of a real lock would be a table this extension does not own and a failure mode where
 * a crashed node holds the lock forever.
 *
 * <p>The claim does expire, for the crash case. A node that dies mid-backup leaves
 * {@code state.runningOn} set; after {@link #STALE_CLAIM} the next node to look treats it as
 * abandoned and says so rather than never backing up again.
 *
 * <h2>History</h2>
 *
 * <p>One row per run, newest kept, oldest dropped past {@link #HISTORY_LIMIT}. It is the
 * answer to the only question anybody asks of a backup system between incidents -- "is it
 * actually running?" -- and it is kept in the database rather than parsed out of the log,
 * because the log is rotated and is per-node.
 */
final class BackupStore {

    private static final Logger LOG = LogManager.getLogger(BackupStore.class);

    private static final String K_LAST_RUN = "state.lastRun";
    private static final String K_LAST_SUCCESS = "state.lastSuccess";
    private static final String K_LAST_ERROR = "state.lastError";
    private static final String K_RUNNING_ON = "state.runningOn";
    private static final String K_RUNNING_SINCE = "state.runningSince";
    private static final String HISTORY_PREFIX = "history.";

    /** Enough to see a month of nightly runs, few enough not to bloat the table. */
    private static final int HISTORY_LIMIT = 60;

    /**
     * After this, a claim left by a node that died is treated as abandoned.
     *
     * <p>Long, deliberately. A full backup of an engine with years of messages on a slow
     * destination legitimately takes hours, and cutting a claim short would have a second
     * node start a duplicate of a backup that is still uploading.
     */
    static final Duration STALE_CLAIM = Duration.ofHours(12);

    private BackupStore() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static Properties stored() {
        try {
            Properties p = config().getPropertiesForGroup(BackupSettings.GROUP);
            return p == null ? new Properties() : p;
        } catch (Exception e) {
            // Before the database is up, or during shutdown. An empty set reads as "never
            // run", which is true enough and harmless.
            LOG.debug("backup: could not read stored state: {}", e.getMessage());
            return new Properties();
        }
    }

    static String get(String key, String fallback) {
        String v = stored().getProperty(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /**
     * {@code saveProperty} is an UPDATE followed by an INSERT when the UPDATE matched
     * nothing, so two nodes creating the same property for the first time can both reach the
     * INSERT and one loses on the unique key. The retry finds the row present and takes the
     * UPDATE path, which is the right outcome either way.
     */
    static void put(String key, String value) {
        try {
            config().saveProperty(BackupSettings.GROUP, key, value == null ? "" : value);
        } catch (RuntimeException first) {
            try {
                config().saveProperty(BackupSettings.GROUP, key, value == null ? "" : value);
            } catch (RuntimeException second) {
                // Never fatal: failing to record that a backup ran must not stop the backup.
                LOG.warn("backup: could not store {}: {}", key, second.getMessage());
            }
        }
    }

    private static void remove(String key) {
        try {
            config().removeProperty(BackupSettings.GROUP, key);
        } catch (Exception e) {
            LOG.debug("backup: could not remove {}: {}", key, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // When
    // ------------------------------------------------------------------

    static Instant lastRun() {
        return instant(get(K_LAST_RUN, ""));
    }

    static Instant lastSuccess() {
        return instant(get(K_LAST_SUCCESS, ""));
    }

    static String lastError() {
        return get(K_LAST_ERROR, "");
    }

    private static Instant instant(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The claim
    // ------------------------------------------------------------------

    /** The node currently running a backup, or null. Not necessarily still alive. */
    static String runningOn() {
        String who = get(K_RUNNING_ON, "");
        return who.isEmpty() ? null : who;
    }

    static Instant runningSince() {
        return instant(get(K_RUNNING_SINCE, ""));
    }

    /** Whether a claim exists and is recent enough to believe. */
    static boolean claimHeld() {
        if (runningOn() == null) {
            return false;
        }
        Instant since = runningSince();
        // A claim with no timestamp, or one from the future, is believed: clocks differ
        // between nodes, and the cost of believing it is one skipped run.
        return since == null || Duration.between(since, Instant.now()).compareTo(STALE_CLAIM) < 0;
    }

    /**
     * Takes the claim, and records the attempt.
     *
     * <p>{@code lastRun} is written here, before the backup starts, rather than when it
     * finishes. A node whose backup is still uploading has therefore already told its
     * neighbours that this window is taken -- which is the point -- and a run that fails
     * still counts as having happened, so a destination that is down does not have three
     * nodes retrying in a loop until it comes back.
     */
    static void claim(String serverId, Instant when) {
        put(K_RUNNING_ON, serverId);
        put(K_RUNNING_SINCE, when.toString());
        put(K_LAST_RUN, when.toString());
    }

    static void release() {
        remove(K_RUNNING_ON);
        remove(K_RUNNING_SINCE);
    }

    static void recordSuccess(Instant when) {
        put(K_LAST_SUCCESS, when.toString());
        put(K_LAST_ERROR, "");
    }

    static void recordFailure(String message) {
        put(K_LAST_ERROR, message == null ? "" : message);
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    /** Fields of a history row, in this order. Mirrored by the console. */
    static final int HISTORY_FIELDS = 9;

    static void record(Instant startedAt, Instant finishedAt, String trigger, String outcome,
                       String archiveName, long bytes, String destination, String summary,
                       String problems) {
        String row = Tsv.join(startedAt, finishedAt, trigger, outcome, archiveName, bytes,
            destination, summary, problems);
        // Keyed on the start time so the rows sort chronologically by key, which is what
        // makes trimming the oldest a matter of taking the first entries off a TreeMap.
        put(HISTORY_PREFIX + startedAt.toEpochMilli(), row);
        trim();
    }

    static List<String> history() {
        Properties p = stored();
        TreeMap<Long, String> rows = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(HISTORY_PREFIX)) {
                continue;
            }
            try {
                rows.put(Long.parseLong(key.substring(HISTORY_PREFIX.length())),
                    p.getProperty(key));
            } catch (NumberFormatException e) {
                // A key this plugin did not write. Left alone rather than repaired.
                continue;
            }
        }
        List<String> out = new ArrayList<>(rows.descendingMap().values());
        return out;
    }

    private static void trim() {
        Properties p = stored();
        TreeMap<Long, String> keys = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(HISTORY_PREFIX)) {
                continue;
            }
            try {
                keys.put(Long.parseLong(key.substring(HISTORY_PREFIX.length())), key);
            } catch (NumberFormatException e) {
                continue;
            }
        }
        while (keys.size() > HISTORY_LIMIT) {
            remove(keys.pollFirstEntry().getValue());
        }
    }
}
