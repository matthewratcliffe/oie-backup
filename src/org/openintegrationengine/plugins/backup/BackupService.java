/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerSettings;
import com.mirth.connect.server.channel.ErrorTaskHandler;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.model.ServerEventContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.plugins.backup.destination.Destination;
import org.openintegrationengine.plugins.backup.destination.Destinations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Taking a backup, and putting one back.
 *
 * <p>Everything here is sequenced rather than parallel, and everything runs under one lock.
 * A backup reads the whole engine and writes a file that can be tens of gigabytes; two of
 * them at once would double the disk, double the load on the message tables and produce two
 * archives whose only difference is a race. So a second request while one is running is
 * told so, rather than queued -- a queued backup is a backup that starts at an unpredictable
 * time, which is the one property a scheduled job must not have.
 *
 * <h2>The order of a backup, and why</h2>
 *
 * <ol>
 *   <li><b>Claim.</b> Written to the shared record before any work, so the other nodes in a
 *       cluster know this window is taken.
 *   <li><b>Configuration, then messages.</b> Configuration is seconds and messages are
 *       hours, so if the run is going to fail on a full disk it should fail before spending
 *       the hours. It also means a partial archive has the more valuable half.
 *   <li><b>Manifest.</b> Written last, after the counts are known, so it describes what is
 *       actually in the archive rather than what was asked for.
 *   <li><b>Zip, then encrypt.</b> In that order, because compressed-then-encrypted is the
 *       only order that compresses at all -- ciphertext does not compress.
 *   <li><b>Upload, then prune.</b> Retention runs only after the new archive is safely
 *       stored. Pruning first would mean a failed upload leaves fewer backups than it found,
 *       which is the one outcome a backup system must never produce.
 * </ol>
 *
 * <h2>The order of a restore, and why</h2>
 *
 * <p>Configuration before messages, always, because a message cannot be imported into a
 * channel that does not exist yet. And code templates before channels inside the
 * configuration step, for the same shape of reason. Neither order is the operator's to get
 * wrong: the console offers "restore these things" and this class sequences them.
 */
public final class BackupService {

    private static final Logger LOG = LogManager.getLogger(BackupService.class);

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Where archives kept on this engine live, and where work happens. */
    private final Path base;
    private final Path staging;
    private final Path work;

    /**
     * One backup or restore at a time on this node. Cluster-wide exclusion is a separate,
     * weaker thing -- see {@link BackupStore}.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /** What is happening now, for a console watching a long run. Never null. */
    private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.idle());

    public BackupService(Path base) {
        this.base = base;
        this.staging = base.resolve("archives");
        this.work = base.resolve("work");
    }

    /** Creates the directories, and says so in the log so the path is findable. */
    public void prepare() throws IOException {
        Files.createDirectories(staging);
        Files.createDirectories(work);
        /*
         * Anything left in work/ is from a run that did not finish -- a container killed
         * mid-backup, most likely. It is scratch space by definition, and leaving it would
         * have it accumulate silently until the volume filled.
         */
        int cleared = deleteChildren(work);
        if (cleared > 0) {
            LOG.info("backup: cleared {} unfinished working director(ies) from {}",
                cleared, work);
        }
    }

    public Path stagingDirectory() {
        return staging;
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /** A snapshot of a running operation, or the fact that nothing is running. */
    static final class Progress {
        final boolean running;
        final String operation;
        final String phase;
        final String detail;
        final Instant startedAt;

        private Progress(boolean running, String operation, String phase, String detail,
                         Instant startedAt) {
            this.running = running;
            this.operation = operation;
            this.phase = phase;
            this.detail = detail;
            this.startedAt = startedAt;
        }

        static Progress idle() {
            return new Progress(false, "", "", "", null);
        }

        Progress at(String newPhase, String newDetail) {
            return new Progress(true, operation, newPhase, newDetail, startedAt);
        }
    }

    private void phase(String phase, String detail) {
        progress.updateAndGet(p -> p.at(phase, detail));
        LOG.info("backup {}: {}{}", progress.get().operation, phase,
            detail == null || detail.isBlank() ? "" : " -- " + detail);
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    public Map<String, Object> status() {
        BackupSettings settings = BackupSettings.load();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);

        out.put("settings", settings.rows());
        out.put("scopes", BackupScope.rows());
        out.put("destinationTypes", catalogueRows());
        out.put("enabled", settings.enabled());
        out.put("valid", settings.validate().isEmpty());
        out.put("problems", settings.validate());

        Schedule schedule = settings.schedule();
        out.put("schedule", schedule.describe());
        out.put("scheduleExpression", schedule.expression());
        Instant next = schedule.nextAfter(BackupStore.lastRun(), Instant.now());
        out.put("nextRun", next == null ? "" : next.toString());

        out.put("lastRun", text(BackupStore.lastRun()));
        out.put("lastSuccess", text(BackupStore.lastSuccess()));
        out.put("lastError", BackupStore.lastError());

        Progress current = progress.get();
        out.put("running", current.running);
        out.put("operation", current.operation);
        out.put("phase", current.phase);
        out.put("phaseDetail", current.detail);
        out.put("runningSince", text(current.startedAt));
        out.put("clusterRunningOn", BackupStore.claimHeld()
            ? String.valueOf(BackupStore.runningOn()) : "");
        out.put("thisServerId", serverId());

        out.put("history", BackupStore.history());
        out.put("historyFields", BackupStore.HISTORY_FIELDS);

        out.put("destination", describeDestination(settings));
        out.put("hasPassphrase", settings.hasPassphrase());
        out.put("channels", channelRows());
        return out;
    }

    private static List<String> catalogueRows() {
        List<String> out = new ArrayList<>();
        for (String[] row : Destinations.catalogue()) {
            out.add(Tsv.join((Object[]) row));
        }
        return out;
    }

    /**
     * Describes the destination without connecting to it.
     *
     * <p>Status is read on every page load, and opening an SFTP session or signing an S3
     * request to draw a heading would make the page as slow and as failure-prone as the
     * remote storage is.
     */
    private String describeDestination(BackupSettings settings) {
        String type = settings.destinationType();
        if (Destinations.KEEP.equals(type)) {
            return "Kept on this engine (" + staging + ")";
        }
        try (Destination destination = Destinations.open(settings.destination(), staging)) {
            return destination.describe();
        } catch (Exception e) {
            // Constructing an SFTP or FTP destination connects, so this is where an
            // unreachable host shows up. The type is still worth reporting.
            return type + " (not reachable: " + message(e) + ")";
        }
    }

    /** Every channel, so the console can offer a message selection: {@code id \t name}. */
    private static List<String> channelRows() {
        List<String> out = new ArrayList<>();
        List<Channel> all = ControllerFactory.getFactory()
            .createChannelController().getChannels(null);
        if (all == null) {
            return out;
        }
        List<Channel> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparing(c -> c.getName() == null ? "" : c.getName(),
            String.CASE_INSENSITIVE_ORDER));
        for (Channel channel : ordered) {
            out.add(Tsv.join(channel.getId(), channel.getName()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Taking a backup
    // ------------------------------------------------------------------

    /**
     * Runs a backup now.
     *
     * @param trigger  "scheduled" or "manual", recorded in the history and the manifest
     * @param respectClaim true for a scheduled run, which must not start one a neighbour is
     *                     already doing; false for one somebody asked for, where being told
     *                     "another node is busy" is an answer, not a refusal
     */
    public Map<String, Object> runBackup(String trigger, boolean respectClaim) {
        if (!lock.tryLock()) {
            return busy("a backup or restore is already running on this node");
        }
        try {
            if (respectClaim && BackupStore.claimHeld()) {
                return busy("another node (" + BackupStore.runningOn()
                    + ") is already running this backup");
            }
            return takeBackup(trigger);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> takeBackup(String trigger) {
        BackupSettings settings = BackupSettings.load();
        List<String> invalid = settings.validate();
        if (!invalid.isEmpty()) {
            Map<String, Object> out = failure("the settings are not usable");
            out.put("problems", invalid);
            return out;
        }

        Instant startedAt = Instant.now();
        progress.set(new Progress(true, "backup", "starting", "", startedAt));
        BackupStore.claim(serverId(), startedAt);

        Path runDir = null;
        Path archive = null;
        String archiveName = archiveName(startedAt, settings.label(),
            settings.encryptArchives());
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Manifest manifest = new Manifest();

        try {
            runDir = Files.createDirectories(work.resolve("backup-" + startedAt.toEpochMilli()));

            manifest.put(Manifest.K_PLUGIN_VERSION, version())
                .put(Manifest.K_CREATED_AT, startedAt.toString())
                .put(Manifest.K_TRIGGER, trigger)
                .put(Manifest.K_LABEL, settings.label())
                .put(Manifest.K_SERVER_ID, serverId())
                .put(Manifest.K_ENGINE_VERSION, engineVersion())
                .put(Manifest.K_ENCRYPTED, settings.encryptArchives());
            ServerSettings server = serverSettings();
            if (server != null) {
                manifest.put(Manifest.K_SERVER_NAME, server.getServerName())
                    .put(Manifest.K_ENVIRONMENT, server.getEnvironmentName());
            }

            if (settings.includeConfig()) {
                phase("configuration", settings.configScope().size() + " part(s)");
                ConfigCapture capture = new ConfigCapture(settings.configScope());
                ConfigCapture.Result result = capture.capture(runDir.resolve("config"));
                problems.addAll(result.problems());
                notes.addAll(result.notes());
                manifest.put(Manifest.K_CONFIG_SCOPES,
                    BackupScope.format(settings.configScope()));
                result.counts().forEach((what, n) ->
                    manifest.put(Manifest.K_CONFIG_COUNT + what, n));
            } else {
                manifest.put(Manifest.K_CONFIG_SCOPES, "");
            }

            if (settings.includeMessages()) {
                MessageCapture.Selection selection = settings.messageSelection();
                phase("messages", selection.describe());
                MessageCapture capture = new MessageCapture(selection);
                MessageCapture.Result result = capture.capture(runDir);
                problems.addAll(result.problems);
                manifest.put(Manifest.K_MESSAGES, true)
                    .put(Manifest.K_MESSAGE_TOTAL, result.total)
                    .put(Manifest.K_MESSAGE_CHANNELS, result.channels.size())
                    .put(Manifest.K_MESSAGE_FROM, selection.days == 0 ? ""
                        : Instant.now().minus(Duration.ofDays(selection.days)).toString())
                    .put(Manifest.K_MESSAGE_TO, startedAt.toString())
                    .put(Manifest.K_MESSAGE_ATTACHMENTS, selection.includeAttachments);
                int index = 0;
                for (String row : result.channels) {
                    // Zero-padded so the manifest's keys sort in the order they were written
                    // rather than 1, 10, 11, 2.
                    manifest.put(Manifest.K_MESSAGE_CHANNEL
                        + String.format(Locale.ROOT, "%05d", index++), row);
                }
            } else {
                manifest.put(Manifest.K_MESSAGES, false);
            }

            if (!problems.isEmpty()) {
                manifest.put(Manifest.K_PROBLEMS, problems.size());
            }
            Files.writeString(runDir.resolve(Manifest.FILE_NAME), manifest.render(),
                StandardCharsets.UTF_8);

            phase("packaging", archiveName);
            Path zip = work.resolve("archive-" + startedAt.toEpochMilli() + ".zip");
            Archive.write(runDir, zip);

            if (settings.encryptArchives()) {
                phase("encrypting", Destinations.bytes(Files.size(zip)));
                Path sealed = work.resolve("archive-" + startedAt.toEpochMilli() + ".zip.enc");
                char[] passphrase = settings.passphrase();
                try {
                    ArchiveCipher.encrypt(zip, sealed, passphrase);
                } finally {
                    java.util.Arrays.fill(passphrase, '\0');
                }
                Files.delete(zip);
                archive = sealed;
            } else {
                archive = zip;
            }

            long bytes = Files.size(archive);
            String destinationLabel;
            phase("uploading", Destinations.bytes(bytes));
            try (Destination destination = Destinations.open(settings.destination(), staging)) {
                destinationLabel = destination.describe();
                destination.put(archiveName, archive);

                if (settings.pruneEnabled()) {
                    phase("retention", "");
                    List<String> removed = prune(destination, settings);
                    if (!removed.isEmpty()) {
                        notes.add("retention removed " + removed.size() + " archive(s): "
                            + String.join(", ", removed));
                    }
                }
            }

            Instant finishedAt = Instant.now();
            String outcome = problems.isEmpty() ? "ok" : "partial";
            BackupStore.recordSuccess(finishedAt);
            if (!problems.isEmpty()) {
                BackupStore.recordFailure(problems.size() + " problem(s); see the history");
            }
            BackupStore.record(startedAt, finishedAt, trigger, outcome, archiveName, bytes,
                destinationLabel, manifest.summary(), String.join(" | ", problems));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("archive", archiveName);
            out.put("bytes", bytes);
            out.put("size", Destinations.bytes(bytes));
            out.put("destination", destinationLabel);
            out.put("summary", manifest.summary());
            out.put("problems", problems);
            out.put("notes", notes);
            out.put("took", Duration.between(startedAt, finishedAt).toSeconds() + "s");
            LOG.info("backup complete: {} ({}) to {}", archiveName,
                Destinations.bytes(bytes), destinationLabel);
            return out;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return recordAndFail(startedAt, trigger, archiveName,
                "the backup was interrupted, most likely by the engine shutting down",
                problems);
        } catch (Exception e) {
            LOG.error("backup failed", e);
            return recordAndFail(startedAt, trigger, archiveName, message(e), problems);
        } finally {
            progress.set(Progress.idle());
            BackupStore.release();
            if (runDir != null) {
                deleteTree(runDir);
            }
            // The archive is deleted from the working directory once it is stored, unless
            // this engine is itself the destination, in which case put() already moved a
            // copy into the staging directory and this one is the leftover.
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException e) {
                    LOG.warn("backup: could not remove the working copy {}: {}",
                        archive, e.getMessage());
                }
            }
        }
    }

    private Map<String, Object> recordAndFail(Instant startedAt, String trigger,
                                              String archiveName, String reason,
                                              List<String> problems) {
        BackupStore.recordFailure(reason);
        BackupStore.record(startedAt, Instant.now(), trigger, "failed", archiveName, 0,
            "", "", reason);
        Map<String, Object> out = failure(reason);
        out.put("problems", problems);
        return out;
    }

    /**
     * The archive's name.
     *
     * <p>UTC, always, and in a form that sorts lexically -- which is what lets retention
     * find the oldest without asking a destination for modification times it may not keep,
     * and what makes a directory listing chronological on every platform.
     */
    private static String archiveName(Instant when, String label, boolean encrypted) {
        return "oie-backup-" + STAMP.format(when)
            + (label == null || label.isEmpty() ? "" : "-" + label)
            + ".zip" + (encrypted ? ArchiveCipher.SUFFIX : "");
    }

    // ------------------------------------------------------------------
    // Archives
    // ------------------------------------------------------------------

    /** What the destination holds: {@code name \t bytes \t size \t modified}. */
    public Map<String, Object> archives() {
        BackupSettings settings = BackupSettings.load();
        Map<String, Object> out = new LinkedHashMap<>();
        try (Destination destination = Destinations.open(settings.destination(), staging)) {
            List<String> rows = new ArrayList<>();
            for (Destination.RemoteFile file : destination.list()) {
                rows.add(Tsv.join(file.name, file.size, Destinations.bytes(file.size),
                    file.modified == null ? "" : file.modified.toString(),
                    ArchiveCipher.isEncryptedName(file.name)));
            }
            out.put("ok", Boolean.TRUE);
            out.put("archives", rows);
            out.put("destination", destination.describe());
            out.put("local", Destinations.KEEP.equals(settings.destinationType()));
            out.put("prunePreview", prunePreview(destination, settings));
            return out;
        } catch (Exception e) {
            return failure(message(e));
        }
    }

    /** A connection test that writes and removes a marker. */
    public Map<String, Object> probe() {
        BackupSettings settings = BackupSettings.load();
        try (Destination destination = Destinations.open(settings.destination(), staging)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("destination", destination.describe());
            out.put("result", destination.probe());
            return out;
        } catch (Exception e) {
            return failure(message(e));
        }
    }

    /**
     * Removes one archive, by name.
     *
     * <p>Irreversible, so the servlet requires the name to be sent explicitly and the
     * console requires a confirmation. There is no "delete all".
     */
    public Map<String, Object> deleteArchive(String name) {
        BackupSettings settings = BackupSettings.load();
        try (Destination destination = Destinations.open(settings.destination(), staging)) {
            String simple = Destinations.requireSimpleName(name);
            if (!Destinations.isArchiveName(simple)) {
                return failure("'" + simple + "' is not the name of an archive this plugin "
                    + "wrote, so it will not be deleted");
            }
            destination.delete(simple);
            LOG.warn("backup: deleted archive {} from {}", simple, destination.describe());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("deleted", simple);
            return out;
        } catch (Exception e) {
            return failure(message(e));
        }
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    /**
     * Which archives the current limits would remove, without removing them.
     *
     * <p>The reason this exists as its own call: deleting a backup cannot be undone, and an
     * operator turning retention on deserves to see the actual list of files it is about to
     * start deleting rather than a rule they have to evaluate in their head against a
     * directory listing.
     */
    private List<String> prunePreview(Destination destination, BackupSettings settings) {
        try {
            List<String> names = new ArrayList<>();
            for (Destination.RemoteFile file : doomed(destination.list(), settings)) {
                names.add(file.name);
            }
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> prune(Destination destination, BackupSettings settings)
            throws IOException {
        List<String> removed = new ArrayList<>();
        for (Destination.RemoteFile file : doomed(destination.list(), settings)) {
            try {
                destination.delete(file.name);
                removed.add(file.name);
                LOG.warn("backup: retention removed {} from {}", file.name,
                    destination.describe());
            } catch (IOException e) {
                LOG.warn("backup: retention could not remove {}: {}", file.name,
                    e.getMessage());
            }
        }
        return removed;
    }

    /**
     * The archives the limits select for removal.
     *
     * <p>Two rules, and an archive goes when <b>either</b> selects it: past the count, or
     * older than the age. An archive is never removed for being old if doing so would take
     * the count below the keep-count -- because "keep 7" and "keep 30 days" together should
     * mean "at least 7, and nothing older than 30 days beyond that", not "nothing at all
     * after a quiet month". A backup system that empties itself because nothing ran for a
     * while is a trap, not a policy.
     */
    private static List<Destination.RemoteFile> doomed(List<Destination.RemoteFile> all,
                                                       BackupSettings settings) {
        List<Destination.RemoteFile> sorted = new ArrayList<>(all);
        // Newest first. The name is a UTC timestamp, so it sorts correctly and does not
        // depend on the destination reporting modification times at all.
        sorted.sort(Comparator.comparing((Destination.RemoteFile f) -> f.name).reversed());

        int keepCount = settings.keepCount();
        int keepDays = settings.keepDays();
        List<Destination.RemoteFile> out = new ArrayList<>();
        Instant cutoff = keepDays > 0
            ? Instant.now().minus(Duration.ofDays(keepDays)) : null;

        for (int i = 0; i < sorted.size(); i++) {
            Destination.RemoteFile file = sorted.get(i);
            boolean pastCount = keepCount > 0 && i >= keepCount;
            boolean tooOld = false;
            if (cutoff != null && (keepCount == 0 || i >= keepCount)) {
                Instant when = stampOf(file.name);
                if (when == null) {
                    when = file.modified;
                }
                tooOld = when != null && when.isBefore(cutoff);
            }
            if (pastCount || tooOld) {
                out.add(file);
            }
        }
        return out;
    }

    /** The timestamp out of an archive name, or null when it is not one of ours. */
    private static Instant stampOf(String name) {
        if (name == null || !name.startsWith("oie-backup-") || name.length() < 27) {
            return null;
        }
        try {
            return java.time.LocalDateTime
                .parse(name.substring(11, 26), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Inspecting an archive
    // ------------------------------------------------------------------

    /**
     * Reads an archive's manifest without restoring anything.
     *
     * <p>The whole file is fetched to do it, which is the honest cost of an archive that
     * lives on somebody else's storage. It is worth it: choosing between six backups by
     * name and timestamp alone is how the wrong one gets restored.
     */
    public Map<String, Object> inspect(String name, String passphrase) {
        if (!lock.tryLock()) {
            return busy("a backup or restore is running");
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory(work, "inspect-");
            Path archive = obtain(name, scratch, passphrase);
            try (Archive zip = Archive.open(archive)) {
                Manifest manifest = Manifest.from(zip);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", Boolean.TRUE);
                out.put("name", name);
                out.put("manifest", manifest.rows());
                out.put("summary", manifest.summary());
                out.put("createdAt", text(manifest.createdAt()));
                out.put("scopes", BackupScope.format(manifest.scopes()));
                out.put("counts", manifest.counts());
                out.put("messageChannels", manifest.messageChannels());
                out.put("entries", zip.entries().size());
                return out;
            }
        } catch (ArchiveCipher.BadPassphraseException e) {
            Map<String, Object> out = failure(e.getMessage());
            out.put("needsPassphrase", Boolean.TRUE);
            return out;
        } catch (Exception e) {
            Map<String, Object> out = failure(message(e));
            if (ArchiveCipher.isEncryptedName(name)) {
                out.put("needsPassphrase", Boolean.TRUE);
            }
            return out;
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Restoring
    // ------------------------------------------------------------------

    /**
     * Puts an archive back into this engine.
     *
     * @param name        the archive, in the configured destination or the upload directory
     * @param scopeCsv    which configuration parts to restore: null for everything the
     *                    archive holds, {@code none} for no configuration, otherwise
     *                    exactly the scopes named -- an empty list restores nothing
     * @param channelsCsv which channels' messages to restore; empty means all of them,
     *                    {@code none} means no messages at all
     * @param passphrase  for an encrypted archive; the stored one is used when this is empty
     * @param safetyFirst take a configuration-only backup of this engine first
     * @param redeploy    redeploy every channel afterwards
     * @param deployForMessages deploy the channels the messages belong to, if they are not
     *                          deployed, before importing. The engine cannot import into an
     *                          undeployed channel, and deploying one starts it processing,
     *                          so this is off unless asked for
     * @param replace     make the engine <b>match</b> the archive for the selected scopes:
     *                    objects the archive does not describe are removed. Off unless
     *                    asked for, because removing a channel destroys its message history
     * @param actingUser  the username performing the restore, never removed by
     *                    {@code replace}; null when it is not known
     */
    public Map<String, Object> restore(String name, String scopeCsv, String channelsCsv,
                                       String passphrase, boolean safetyFirst,
                                       boolean redeploy, boolean deployForMessages,
                                       boolean replace, String actingUser) {
        if (!lock.tryLock()) {
            return busy("a backup or restore is already running on this node");
        }
        Instant startedAt = Instant.now();
        progress.set(new Progress(true, "restore", "starting", name, startedAt));

        Path scratch = null;
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            String safetyArchive = "";
            if (safetyFirst) {
                phase("safety backup", "configuration only, kept on this engine");
                Map<String, Object> safety = takeSafetyBackup();
                if (!Boolean.TRUE.equals(safety.get("ok"))) {
                    // Refused rather than continued. The operator asked for a net; starting
                    // the restore without one would be answering a different question.
                    return failure("the safety backup failed, so the restore was not "
                        + "started: " + safety.get("error"));
                }
                safetyArchive = String.valueOf(safety.get("archive"));
                notes.add("a configuration-only backup of this engine was taken first, as "
                    + safetyArchive + ", and is kept on this engine");
                progress.set(new Progress(true, "restore", "starting", name, startedAt));
            }

            scratch = Files.createTempDirectory(work, "restore-");
            phase("fetching", name);
            Path archive = obtain(name, scratch, passphrase);

            phase("unpacking", "");
            Path tree = scratch.resolve("tree");
            try (Archive zip = Archive.open(archive)) {
                zip.extract("", tree);
            }

            Manifest manifest = Manifest.parse(
                Files.exists(tree.resolve(Manifest.FILE_NAME))
                    ? Files.readString(tree.resolve(Manifest.FILE_NAME), StandardCharsets.UTF_8)
                    : "");

            Path configDir = tree.resolve("config");
            /*
             * The scopes come from the files on disk, not from the manifest. The manifest
             * has been on somebody else's storage and can be edited; the extracted tree is
             * what would actually be applied. Where they disagree, the tree wins and the
             * difference is reported rather than hidden.
             */
            Set<BackupScope> present = Files.isDirectory(configDir)
                ? ConfigCapture.scopesPresent(configDir) : Set.of();
            /*
             * Three cases, and the middle one is why this is not a one-liner.
             *
             *   absent   the caller did not mention configuration: everything the archive
             *            holds, which is the convenient default for a scripted restore
             *   "none"   configuration explicitly not wanted
             *   a list   exactly those, and parsed strictly -- an empty or unrecognisable
             *            list means nothing, never "all of them"
             *
             * The console always sends the explicit form. The distinction exists because
             * an empty string is what a form posts for "I unticked every box", and reading
             * that as "restore all thirteen" would overwrite this engine's channels, its
             * alerts and its user accounts while claiming to have honoured a selection.
             */
            Set<BackupScope> wanted;
            if (scopeCsv == null) {
                wanted = present;
            } else if ("none".equalsIgnoreCase(scopeCsv.trim())) {
                wanted = Set.of();
            } else {
                wanted = BackupScope.parseStrict(scopeCsv);
            }
            Set<BackupScope> selected = new LinkedHashSet<>(wanted);
            selected.retainAll(present);

            Set<BackupScope> missing = new LinkedHashSet<>(wanted);
            missing.removeAll(present);
            for (BackupScope scope : missing) {
                notes.add(scope.label() + " was asked for but is not in this archive");
            }

            /*
             * The same three cases as scope, for the same reason. Null is every channel in
             * the archive; "none" is no messages; a list is exactly those channels, and an
             * empty list is no messages rather than all of them.
             */
            Set<String> onlyChannels = new LinkedHashSet<>();
            boolean allChannels = channelsCsv == null;
            if (channelsCsv != null && !"none".equalsIgnoreCase(channelsCsv.trim())) {
                for (String part : channelsCsv.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) {
                        onlyChannels.add(t);
                    }
                }
            }
            boolean wantsMessages = allChannels || !onlyChannels.isEmpty();
            boolean hasMessages = Files.isDirectory(tree.resolve(MessageCapture.DIRECTORY));
            if (selected.isEmpty() && !(wantsMessages && hasMessages)) {
                /*
                 * Refused rather than reported as a successful restore of nothing. A
                 * restore that says "ok" and changed nothing is indistinguishable, in a
                 * history row and in somebody's memory, from one that worked -- and the
                 * moment it matters is the moment nobody can afford to find that out.
                 */
                return failure(wanted.isEmpty() && !wantsMessages
                    ? "nothing was selected to restore"
                    : "nothing in this archive matches what was selected: it holds "
                      + (present.isEmpty() ? "no configuration" : present.size()
                          + " configuration part(s)")
                      + (hasMessages ? " and messages" : " and no messages"));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            if (!selected.isEmpty()) {
                phase("configuration", selected.size() + " part(s)");
                ConfigCapture capture = new ConfigCapture(selected);
                ConfigCapture.Result result = capture.restore(configDir);
                problems.addAll(result.problems());
                notes.addAll(result.notes());
                out.put("configCounts", countRows(result.counts()));

                List<String> invalid = capture.findInvalidChannels(configDir);
                if (!invalid.isEmpty()) {
                    problems.addAll(invalid);
                    notes.add("a channel stored as invalid never deploys and never runs; "
                        + "these were accepted by the engine and then read back as stubs");
                }

                if (replace) {
                    /*
                     * After applying, not before. Applying first means an object that the
                     * archive renames or moves between scopes is matched by id and updated
                     * rather than deleted and recreated -- which for a channel would be the
                     * difference between keeping its message history and destroying it.
                     */
                    phase("removing", "what the archive does not describe");
                    ConfigCapture.Result removed = capture.applyRemovals(configDir, actingUser);
                    problems.addAll(removed.problems());
                    out.put("removedCounts", countRows(removed.counts()));
                    int total = removed.counts().values().stream()
                        .mapToInt(Integer::intValue).sum();
                    notes.add(total == 0
                        ? "nothing needed removing: the engine already held only what the "
                          + "archive describes"
                        : "removed " + total + " object(s) the archive does not describe, "
                          + "including any message history belonging to removed channels");
                }
            }

            if (wantsMessages && hasMessages) {
                if (deployForMessages) {
                    phase("deploying", "channels that will receive messages");
                    notes.addAll(deployForImport(tree, onlyChannels));
                }
                phase("messages", onlyChannels.isEmpty() ? "every channel in the archive"
                    : onlyChannels.size() + " channel(s)");
                MessageCapture capture = new MessageCapture(
                    new MessageCapture.Selection(Set.of(), 0, Set.of(), true));
                MessageCapture.Result result = capture.restore(tree, onlyChannels);
                problems.addAll(result.problems);
                out.put("messagesRestored", result.total);
                out.put("messageChannels", result.channels);
                if (result.total > 0) {
                    notes.add("restored messages are added, not replaced: each one gets a "
                        + "new message id on this engine and keeps its original as an "
                        + "import id, so restoring the same archive twice makes two copies");
                }
            }

            if (redeploy) {
                phase("redeploying", "");
                notes.add(redeployAll());
            } else if (!selected.isEmpty() && selected.contains(BackupScope.CHANNELS)) {
                notes.add("channels were restored but not deployed -- a deployed channel "
                    + "keeps running the version it was deployed with until it is "
                    + "redeployed, so the engine is still processing the old definitions");
            }

            Instant finishedAt = Instant.now();
            String outcome = problems.isEmpty() ? "ok" : "partial";
            BackupStore.record(startedAt, finishedAt, "restore", outcome, name, 0,
                "from " + name, manifest.summary(), String.join(" | ", problems));

            out.put("ok", Boolean.TRUE);
            out.put("archive", name);
            out.put("restoredScopes", BackupScope.format(selected));
            out.put("problems", problems);
            out.put("notes", notes);
            out.put("took", Duration.between(startedAt, finishedAt).toSeconds() + "s");
            LOG.warn("backup: restored {} onto this engine ({} configuration part(s), {})",
                name, selected.size(), problems.isEmpty() ? "no problems"
                    : problems.size() + " problem(s)");
            return out;

        } catch (ArchiveCipher.BadPassphraseException e) {
            Map<String, Object> out = failure(e.getMessage());
            out.put("needsPassphrase", Boolean.TRUE);
            return out;
        } catch (Exception e) {
            LOG.error("backup: restore of {} failed", name, e);
            BackupStore.record(startedAt, Instant.now(), "restore", "failed", name, 0, "",
                "", message(e));
            Map<String, Object> out = failure(message(e));
            out.put("problems", problems);
            out.put("notes", notes);
            return out;
        } finally {
            progress.set(Progress.idle());
            if (scratch != null) {
                deleteTree(scratch);
            }
            lock.unlock();
        }
    }

    /**
     * What a replacing restore would remove, without removing it.
     *
     * <p>Its own call, and the console requires it before it will enable a replacing
     * restore. Deleting a channel takes its message history with it and there is no undo,
     * so the operator confirms against the actual list of objects rather than against an
     * idea of it -- the same shape as the Git Sync extension's review-then-switch.
     *
     * <p>Costs a fetch and an unpack of the archive, which is the honest price of an
     * answer that is about this engine <i>and</i> that archive together.
     */
    public Map<String, Object> previewRemovals(String name, String scopeCsv,
                                               String passphrase, String actingUser) {
        if (!lock.tryLock()) {
            return busy("a backup or restore is running");
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory(work, "preview-");
            Path archive = obtain(name, scratch, passphrase);
            Path tree = scratch.resolve("tree");
            try (Archive zip = Archive.open(archive)) {
                zip.extract("", tree);
            }
            Path configDir = tree.resolve("config");
            Set<BackupScope> present = Files.isDirectory(configDir)
                ? ConfigCapture.scopesPresent(configDir) : Set.of();
            Set<BackupScope> wanted = scopeCsv == null
                ? present
                : ("none".equalsIgnoreCase(scopeCsv.trim())
                    ? Set.<BackupScope>of() : BackupScope.parseStrict(scopeCsv));
            Set<BackupScope> selected = new LinkedHashSet<>(wanted);
            selected.retainAll(present);

            List<String> rows = new ArrayList<>();
            boolean destroysHistory = false;
            if (!selected.isEmpty()) {
                for (ConfigCapture.Removal removal :
                        new ConfigCapture(selected).findRemovals(configDir, actingUser)) {
                    rows.add(removal.row());
                    destroysHistory |= removal.destroysHistory;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("name", name);
            out.put("scope", BackupScope.format(selected));
            out.put("removals", rows);
            out.put("removalFields", ConfigCapture.REMOVAL_FIELDS);
            out.put("destroysHistory", destroysHistory);
            return out;
        } catch (ArchiveCipher.BadPassphraseException e) {
            Map<String, Object> out = failure(e.getMessage());
            out.put("needsPassphrase", Boolean.TRUE);
            return out;
        } catch (Exception e) {
            return failure(message(e));
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
            lock.unlock();
        }
    }

    /**
     * A configuration-only backup kept on this engine, taken before a restore.
     *
     * <p>Configuration only, and deliberately: including the message history would turn a
     * safety net into an hours-long operation that an operator in the middle of an incident
     * would be right to skip -- and a skipped net is no net. Configuration is the part a
     * restore overwrites, so it is the part worth being able to undo.
     */
    private Map<String, Object> takeSafetyBackup() {
        Instant startedAt = Instant.now();
        Path runDir = null;
        try {
            runDir = Files.createDirectories(
                work.resolve("safety-" + startedAt.toEpochMilli()));
            ConfigCapture capture = new ConfigCapture(BackupScope.defaults());
            ConfigCapture.Result result = capture.capture(runDir.resolve("config"));

            Manifest manifest = new Manifest()
                .put(Manifest.K_PLUGIN_VERSION, version())
                .put(Manifest.K_CREATED_AT, startedAt.toString())
                .put(Manifest.K_TRIGGER, "before-restore")
                .put(Manifest.K_LABEL, "before-restore")
                .put(Manifest.K_SERVER_ID, serverId())
                .put(Manifest.K_ENGINE_VERSION, engineVersion())
                .put(Manifest.K_CONFIG_SCOPES, BackupScope.format(BackupScope.defaults()))
                .put(Manifest.K_MESSAGES, false)
                .put(Manifest.K_ENCRYPTED, false);
            result.counts().forEach((what, n) ->
                manifest.put(Manifest.K_CONFIG_COUNT + what, n));
            Files.writeString(runDir.resolve(Manifest.FILE_NAME), manifest.render(),
                StandardCharsets.UTF_8);

            String name = archiveName(startedAt, "before-restore", false);
            Path zip = staging.resolve(name);
            Archive.write(runDir, zip);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("archive", name);
            out.put("bytes", Files.size(zip));
            return out;
        } catch (Exception e) {
            return failure(message(e));
        } finally {
            if (runDir != null) {
                deleteTree(runDir);
            }
        }
    }

    /**
     * Deploys the channels whose messages are about to be imported, where they are not
     * already deployed.
     *
     * <p>Needed because the engine creates a channel's message tables at deploy time and
     * refuses an import into a channel it has not deployed. It is not done automatically:
     * deploying a channel <b>starts it</b>, which on a restored production configuration
     * means it begins listening, polling and sending. That is the operator's call, so it is
     * a checkbox, and this method only runs when it is ticked.
     *
     * <p>Channels that were already deployed are left alone, and nothing is undeployed
     * afterwards -- undeploying something that was already running because this happened to
     * touch it would be worse than the problem. The report names what was deployed.
     */
    private List<String> deployForImport(Path tree, Set<String> only) {
        List<String> notes = new ArrayList<>();
        EngineController engine = ControllerFactory.getFactory().createEngineController();

        Set<String> wanted = new LinkedHashSet<>();
        for (String row : MessageCapture.channelsIn(tree)) {
            String id = row.split("\t", -1)[0];
            if (!id.isEmpty() && (only.isEmpty() || only.contains(id))) {
                wanted.add(id);
            }
        }

        Set<String> toDeploy = new LinkedHashSet<>();
        for (String id : wanted) {
            try {
                if (!engine.isDeployed(id)) {
                    toDeploy.add(id);
                }
            } catch (RuntimeException e) {
                // A channel this engine does not have. The import reports it by name.
                continue;
            }
        }
        if (toDeploy.isEmpty()) {
            return notes;
        }

        ErrorTaskHandler handler = new ErrorTaskHandler();
        engine.deployChannels(toDeploy, ServerEventContext.SYSTEM_USER_EVENT_CONTEXT,
            handler, null);
        if (handler.isErrored()) {
            notes.add("could not deploy " + toDeploy.size() + " channel(s) before importing "
                + "their messages: " + message(handler.getError()));
        } else {
            LOG.warn("backup: deployed {} channel(s) so their messages could be imported; "
                + "they are now running", toDeploy.size());
            notes.add("deployed " + toDeploy.size() + " channel(s) so their messages could "
                + "be imported -- they are now running, and were not before");
        }
        return notes;
    }

    private String redeployAll() {
        try {
            EngineController engine = ControllerFactory.getFactory().createEngineController();
            ErrorTaskHandler handler = new ErrorTaskHandler();
            engine.redeployAllChannels(ServerEventContext.SYSTEM_USER_EVENT_CONTEXT, handler);
            if (handler.isErrored()) {
                return "every channel was asked to redeploy, and at least one failed: "
                    + message(handler.getError());
            }
            return "every channel was redeployed";
        } catch (Exception e) {
            return "the redeploy could not be started: " + message(e);
        }
    }

    // ------------------------------------------------------------------
    // Getting an archive into the working directory
    // ------------------------------------------------------------------

    /**
     * Fetches an archive and, when it is encrypted, decrypts it.
     *
     * <p>Looks in the upload directory first, then the configured destination. That order
     * matters for the case this is really for: restoring onto an engine that has been
     * rebuilt from nothing, where the destination is not configured yet and the archive is
     * being uploaded from somebody's laptop.
     */
    private Path obtain(String name, Path scratch, String passphrase) throws IOException {
        String simple = Destinations.requireSimpleName(name);
        Path local = uploads().resolve(simple);
        Path fetched = scratch.resolve(simple);

        if (Files.isRegularFile(local)) {
            Files.copy(local, fetched, StandardCopyOption.REPLACE_EXISTING);
        } else {
            BackupSettings settings = BackupSettings.load();
            try (Destination destination =
                     Destinations.open(settings.destination(), staging)) {
                destination.fetch(simple, fetched);
            }
        }

        if (!ArchiveCipher.isEncryptedFile(fetched)) {
            return fetched;
        }
        char[] key = passphrase == null || passphrase.isEmpty()
            ? BackupSettings.load().passphrase() : passphrase.toCharArray();
        if (key.length == 0) {
            throw new ArchiveCipher.BadPassphraseException();
        }
        try {
            Path opened = scratch.resolve("decrypted.zip");
            ArchiveCipher.decrypt(fetched, opened, key);
            Files.deleteIfExists(fetched);
            return opened;
        } finally {
            java.util.Arrays.fill(key, '\0');
        }
    }

    /** Where an archive uploaded from the console lands. */
    public Path uploads() {
        return base.resolve("uploads");
    }

    /**
     * Takes an archive uploaded from the console.
     *
     * <p>Bounded: a request body that keeps arriving would otherwise fill the volume the
     * engine's own data is on. The limit is high enough for a real backup and finite, which
     * is the property that matters.
     */
    public Map<String, Object> acceptUpload(String name, InputStream body) {
        try {
            String simple = Destinations.requireSimpleName(name);
            if (!Destinations.isArchiveName(simple)) {
                return failure("'" + simple + "' is not the name of an archive this plugin "
                    + "wrote. Upload the file exactly as it was written, with its original "
                    + "name.");
            }
            Path dir = Files.createDirectories(uploads());
            Path target = dir.resolve(simple);
            Path temp = dir.resolve(simple + ".part");

            long written = 0;
            byte[] buffer = new byte[1 << 16];
            try (java.io.OutputStream out = Files.newOutputStream(temp)) {
                int n;
                while ((n = body.read(buffer)) >= 0) {
                    written += n;
                    if (written > MAX_UPLOAD_BYTES) {
                        throw new IOException("the upload is larger than "
                            + (MAX_UPLOAD_BYTES >> 30) + " GB; copy it to the destination "
                            + "storage and restore from there instead");
                    }
                    out.write(buffer, 0, n);
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("name", simple);
            out.put("bytes", written);
            out.put("size", Destinations.bytes(written));
            out.put("encrypted", ArchiveCipher.isEncryptedFile(target));
            return out;
        } catch (Exception e) {
            return failure(message(e));
        }
    }

    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024 * 1024;

    /** Archives uploaded but not yet restored, so the console can offer and clear them. */
    public List<String> uploadedArchives() {
        List<String> out = new ArrayList<>();
        Path dir = uploads();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = p.getFileName().toString();
                if (Destinations.isArchiveName(name)) {
                    out.add(Tsv.join(name, Files.size(p),
                        Destinations.bytes(Files.size(p)),
                        Files.getLastModifiedTime(p).toInstant().toString(),
                        ArchiveCipher.isEncryptedName(name)));
                }
            }
        } catch (IOException e) {
            LOG.debug("backup: could not list uploads: {}", e.getMessage());
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /**
     * Removes an uploaded archive from this engine.
     *
     * <p>Worth having its own call rather than leaving uploads to accumulate: an uploaded
     * archive is a complete copy of another engine's credentials and messages sitting on
     * this one's disk, and the moment it has been restored is the moment it should stop
     * being there.
     */
    public Map<String, Object> deleteUpload(String name) {
        try {
            String simple = Destinations.requireSimpleName(name);
            if (!Destinations.isArchiveName(simple)) {
                return failure("'" + simple + "' is not the name of an archive this plugin "
                    + "wrote, so it will not be deleted");
            }
            boolean removed = Files.deleteIfExists(uploads().resolve(simple));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("deleted", removed ? simple : "");
            out.put("uploads", uploadedArchives());
            return out;
        } catch (Exception e) {
            return failure(message(e));
        }
    }

    /**
     * The file behind a download request, or null.
     *
     * <p>Only ever a file in the staging directory: an archive on S3 is downloaded from S3,
     * not proxied through the engine, and letting this resolve anywhere else would make it
     * a file-read endpoint with a friendly name.
     */
    public Path downloadable(String name) {
        try {
            Path file = staging.resolve(Destinations.requireSimpleName(name));
            return Files.isRegularFile(file) && Destinations.isArchiveName(
                file.getFileName().toString()) ? file : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public Map<String, Object> saveSettings(Map<String, String> incoming) {
        BackupSettings settings = BackupSettings.load();
        settings.save(incoming == null ? Map.of() : incoming);

        BackupSettings reloaded = BackupSettings.load();
        List<String> problems = reloaded.validate();
        Map<String, Object> out = new LinkedHashMap<>();
        /*
         * Saved even when it does not validate, and the response says both. A settings form
         * that refuses the whole save because one field is wrong loses the other nine, and
         * an operator halfway through configuring an S3 bucket has not made a mistake -- they
         * have made progress.
         */
        out.put("ok", Boolean.TRUE);
        out.put("saved", Boolean.TRUE);
        out.put("valid", problems.isEmpty());
        out.put("problems", problems);
        out.put("settings", reloaded.rows());
        Schedule schedule = reloaded.schedule();
        out.put("schedule", schedule.describe());
        out.put("scheduleExpression", schedule.expression());
        Instant next = schedule.nextAfter(BackupStore.lastRun(), Instant.now());
        out.put("nextRun", next == null ? "" : next.toString());
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Whether the schedule says a backup is owed. Called once a minute. */
    boolean isDue() {
        BackupSettings settings = BackupSettings.load();
        if (!settings.enabled() || !settings.schedule().scheduled()) {
            return false;
        }
        if (!settings.validate().isEmpty()) {
            return false;
        }
        return settings.schedule().isDue(BackupStore.lastRun(), Instant.now());
    }

    private static List<String> countRows(Map<String, Integer> counts) {
        List<String> out = new ArrayList<>();
        counts.forEach((what, n) -> out.add(Tsv.join(what, n)));
        return out;
    }

    private static ConfigurationController configuration() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static String serverId() {
        try {
            String id = configuration().getServerId();
            return id == null ? "" : id;
        } catch (Exception e) {
            return "";
        }
    }

    private static String engineVersion() {
        try {
            String v = configuration().getServerVersion();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private static ServerSettings serverSettings() {
        try {
            return configuration().getServerSettings();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * This extension's version, for the manifest.
     *
     * <p>Read from the engine's own extension metadata rather than from the jar's
     * {@code Implementation-Version}. The jar is built with a plain {@code jar cf} and has
     * no version in its manifest, so the obvious call returns null and every archive would
     * claim to have been written by "dev" -- which is exactly the field somebody checks
     * when an old archive will not restore.
     */
    private static String version() {
        try {
            com.mirth.connect.model.PluginMetaData metaData = ControllerFactory.getFactory()
                .createExtensionController().getPluginMetaData()
                .get(BackupServicePlugin.PLUGIN_POINT_NAME);
            String v = metaData == null ? null : metaData.getPluginVersion();
            return v == null || v.isBlank() ? "unknown" : v;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String text(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        String own = t.getMessage();
        if (own != null && !own.isBlank()) {
            return own;
        }
        Throwable cause = t.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return t.getClass().getSimpleName();
    }

    private static Map<String, Object> failure(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        out.put("error", reason);
        return out;
    }

    private static Map<String, Object> busy(String reason) {
        Map<String, Object> out = failure(reason);
        out.put("busy", Boolean.TRUE);
        return out;
    }

    private static int deleteChildren(Path dir) {
        int n = 0;
        if (!Files.isDirectory(dir)) {
            return n;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                deleteTree(child);
                n++;
            }
        } catch (IOException e) {
            LOG.warn("backup: could not clear {}: {}", dir, e.getMessage());
        }
        return n;
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // Scratch space. Worth a line in the log, never worth failing an operation that
            // has already succeeded.
            LOG.warn("backup: could not remove {}: {}", path, e.getMessage());
        }
    }
}
