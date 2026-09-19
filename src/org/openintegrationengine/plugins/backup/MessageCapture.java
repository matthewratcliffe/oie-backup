/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.MessageImportResult;
import com.mirth.connect.model.filters.MessageFilter;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.MessageController;
import com.mirth.connect.util.messagewriter.MessageWriterOptions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Message history, out of the engine and back into it.
 *
 * <p>Through the engine's own {@code MessageController.exportMessages} and
 * {@code importMessagesServer} -- the same two calls behind the Administrator's Export
 * Messages and Import Messages, writing the same full-message XML, one file per message.
 * Using them rather than reading the message tables directly is what makes an archive
 * restorable onto a different engine and onto a later version: the serialiser migrates a
 * message forward on the way in, and nothing here has to know how the {@code d_mm*} tables
 * are laid out this release.
 *
 * <pre>
 *   messages/channels.tsv        channel id, name and how many messages were written
 *   messages/&lt;channel-id&gt;/1.xml  one file per message, named by message id
 * </pre>
 *
 * <p>Channels are directories named by <b>id</b>, not by name, because the id is what the
 * import needs and a name can change between the backup and the restore. {@code channels.tsv}
 * carries the names so a person reading the archive is not looking at a list of UUIDs.
 *
 * <h2>This is the expensive half, and the one with patients in it</h2>
 *
 * <p>Configuration is megabytes; message history is however many gigabytes the engine has
 * accumulated, and every byte of it is clinical content. Two consequences run through this
 * class. The selection is real -- a date range, a set of channels and a set of statuses,
 * because "everything, forever" is almost never what is wanted and is always what is
 * slowest. And the export runs off the engine's own paged reader with a bounded page size,
 * so a channel with four million messages is streamed to disk rather than assembled in the
 * engine's heap.
 */
final class MessageCapture {

    private static final Logger LOG = LogManager.getLogger(MessageCapture.class);

    /**
     * Messages read per page.
     *
     * <p>The Administrator uses 100. This uses more because the engine is talking to its own
     * database over a local socket and the per-page cost is a round trip, but not so many
     * that one page of large HL7 messages with attachments is a heap problem.
     */
    private static final int PAGE_SIZE = 500;

    /** The Administrator's own file pattern, so an archive is familiar to anyone who looks. */
    private static final String FILE_PATTERN = "${message.messageId}.xml";

    static final String DIRECTORY = "messages";
    static final String CHANNELS_FILE = "messages/channels.tsv";

    /** What to capture. Every field narrows; an empty or zero field means "do not narrow". */
    static final class Selection {
        /** Channel ids. Empty means every channel the engine has. */
        final Set<String> channelIds;
        /** Only messages received in the last N days. 0 means all history. */
        final int days;
        /** Statuses to include. Empty means every status. */
        final Set<Status> statuses;
        final boolean includeAttachments;

        Selection(Set<String> channelIds, int days, Set<Status> statuses,
                  boolean includeAttachments) {
            this.channelIds = channelIds == null ? Set.of() : channelIds;
            this.days = Math.max(0, days);
            this.statuses = statuses == null ? Set.of() : statuses;
            this.includeAttachments = includeAttachments;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(channelIds.isEmpty() ? "every channel"
                : channelIds.size() + " channel(s)");
            sb.append(days == 0 ? ", all history" : ", the last " + days + " day(s)");
            if (!statuses.isEmpty()) {
                sb.append(", status ").append(statuses.stream().map(Enum::name)
                    .sorted().collect(java.util.stream.Collectors.joining("/")));
            }
            sb.append(includeAttachments ? ", with attachments" : ", without attachments");
            return sb.toString();
        }

        /** Parses the status list the settings form posts. Unknown names are ignored. */
        static Set<Status> parseStatuses(String csv) {
            Set<Status> out = new LinkedHashSet<>();
            if (csv == null || csv.isBlank()) {
                return out;
            }
            for (String part : csv.split(",")) {
                String t = part.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                for (Status status : Status.values()) {
                    if (status.name().equals(t)) {
                        out.add(status);
                    }
                }
            }
            return out;
        }
    }

    /** What a capture produced, per channel and in total. */
    static final class Result {
        final List<String> channels = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        long total;

        boolean ok() {
            return problems.isEmpty();
        }
    }

    private final Selection selection;

    MessageCapture(Selection selection) {
        this.selection = selection;
    }

    private static MessageController messages() {
        return ControllerFactory.getFactory().createMessageController();
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    // ------------------------------------------------------------------
    // Engine -> files
    // ------------------------------------------------------------------

    /**
     * Writes the selected messages under {@code root}.
     *
     * <p>A channel that fails is recorded and the rest still run. The reason is the same as
     * in {@link ConfigCapture}: a backup that has nineteen channels out of twenty, and says
     * which one is missing, is worth incomparably more than no backup because one channel's
     * table had a row the serialiser choked on.
     *
     * @throws InterruptedException when the engine is shutting down, which must propagate
     */
    Result capture(Path root) throws IOException, InterruptedException {
        Result result = new Result();
        Path base = Files.createDirectories(root.resolve(DIRECTORY));

        List<Channel> all = channels().getChannels(
            selection.channelIds.isEmpty() ? null : selection.channelIds);
        if (all == null) {
            all = List.of();
        }
        List<Channel> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparing(c -> c.getName() == null ? "" : c.getName(),
            String.CASE_INSENSITIVE_ORDER));

        StringBuilder index = new StringBuilder();
        for (Channel channel : ordered) {
            String id = channel.getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            Path dir = Files.createDirectories(base.resolve(id));
            int written = 0;
            try {
                MessageWriterOptions options = new MessageWriterOptions();
                /*
                 * A null content type is what makes the writer serialise the whole Message
                 * rather than one piece of its content, and the whole Message is the only
                 * thing importMessagesServer can read back. Setting this to RAW would
                 * produce a directory of HL7 that looks like a backup and cannot be
                 * restored -- which is a mistake you find out about exactly once.
                 */
                options.setContentType(null);
                options.setDestinationContent(false);
                options.setEncrypt(false);
                options.setIncludeAttachments(selection.includeAttachments);
                options.setRootFolder(dir.toAbsolutePath().toString());
                options.setFilePattern(FILE_PATTERN);

                written = messages().exportMessages(id, filter(), PAGE_SIZE, options);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                result.problems.add(name(channel) + ": " + describe(e));
                LOG.warn("backup could not export messages for channel {}", id, e);
            }

            if (written == 0) {
                // An empty directory carries nothing and confuses a restore's channel list,
                // so it goes rather than sitting there implying a channel was captured.
                Files.deleteIfExists(dir);
            }
            result.total += written;
            index.append(Tsv.join(id, name(channel), written)).append('\n');
            result.channels.add(Tsv.join(id, name(channel), written));
        }

        Files.writeString(root.resolve(CHANNELS_FILE), index.toString(),
            StandardCharsets.UTF_8);
        return result;
    }

    /**
     * The filter the export runs with.
     *
     * <p>Only the fields the selection sets are touched. A {@link MessageFilter} with
     * nothing on it means every message, which is what an unnarrowed selection should do.
     */
    private MessageFilter filter() {
        MessageFilter filter = new MessageFilter();
        if (selection.days > 0) {
            Calendar from = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            from.setTimeInMillis(Instant.now().minus(selection.days, ChronoUnit.DAYS)
                .toEpochMilli());
            filter.setStartDate(from);
        }
        if (!selection.statuses.isEmpty()) {
            filter.setStatuses(new LinkedHashSet<>(selection.statuses));
        }
        return filter;
    }

    // ------------------------------------------------------------------
    // Files -> engine
    // ------------------------------------------------------------------

    /**
     * Imports the messages under {@code root} back into their channels.
     *
     * <p>Messages are <b>added</b>, never replaced: the import assigns each one a new local
     * message id and records the original as its import id. So restoring the same archive
     * twice produces two copies rather than one, which is worth knowing before doing it --
     * the console says so, and the report says how many went in.
     *
     * <p>A channel directory whose id this engine does not have is skipped and named. That
     * is the common case when messages are restored onto a rebuilt engine before its
     * channels are: the fix is to restore the configuration first, which the console
     * sequences automatically when both are selected.
     *
     * @param only channel ids to restore, or empty for everything in the archive
     */
    Result restore(Path root, Set<String> only) {
        Result result = new Result();
        Path base = root.resolve(DIRECTORY);
        if (!Files.isDirectory(base)) {
            return result;
        }

        List<String> names = readIndex(root);
        try (java.util.stream.Stream<Path> dirs = Files.list(base)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory).sorted()::iterator) {
                String id = dir.getFileName().toString();
                if (!only.isEmpty() && !only.contains(id)) {
                    continue;
                }
                String label = nameFor(names, id);
                List<Channel> found = channels().getChannels(Set.of(id));
                if (found == null || found.isEmpty()) {
                    result.problems.add(label + " (" + id + "): this engine has no such "
                        + "channel, so its messages were skipped. Restore the configuration "
                        + "first, then the messages.");
                    continue;
                }
                /*
                 * Checked here rather than left to fail inside the import. The engine
                 * refuses to import into a channel it has not deployed -- the message
                 * tables for a channel are created at deploy time -- and the exception it
                 * raises says so per message, which on a channel with four million of them
                 * is four million lines of the same sentence. Asking first turns that into
                 * one line that names the channel and the fix.
                 */
                if (!ControllerFactory.getFactory().createEngineController().isDeployed(id)) {
                    result.problems.add(label + " (" + id + "): the channel exists but is "
                        + "not deployed, and the engine cannot import messages into an "
                        + "undeployed channel. Deploy it and restore the messages again, "
                        + "or tick \"deploy these channels first\" on the restore.");
                    continue;
                }
                try {
                    MessageImportResult imported = messages().importMessagesServer(
                        id, dir.toAbsolutePath().toString(), true);
                    int ok = imported == null ? 0 : imported.getSuccessCount();
                    int total = imported == null ? 0 : imported.getTotalCount();
                    result.total += ok;
                    result.channels.add(Tsv.join(id, label, ok));
                    if (total > ok) {
                        result.problems.add(label + ": " + (total - ok) + " of " + total
                            + " message(s) could not be imported");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.problems.add(label + ": the restore was interrupted");
                    return result;
                } catch (Exception e) {
                    result.problems.add(label + ": " + describe(e));
                    LOG.warn("backup could not import messages for channel {}", id, e);
                }
            }
        } catch (IOException e) {
            result.problems.add("could not read the archive's message directory: "
                + e.getMessage());
        }
        return result;
    }

    /** The channels an archive holds messages for: {@code id \t name \t count}. */
    static List<String> channelsIn(Path root) {
        List<String> rows = readIndex(root);
        if (!rows.isEmpty()) {
            return rows;
        }
        // No index -- an archive assembled by hand, or one from a future version whose
        // index moved. The directories are still the truth, so they are used directly.
        List<String> out = new ArrayList<>();
        Path base = root.resolve(DIRECTORY);
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (java.util.stream.Stream<Path> dirs = Files.list(base)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory).sorted()::iterator) {
                long count;
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    count = files.filter(Files::isRegularFile).count();
                }
                out.add(Tsv.join(dir.getFileName().toString(), "", count));
            }
        } catch (IOException e) {
            LOG.debug("backup could not list message directories: {}", e.getMessage());
        }
        return out;
    }

    private static List<String> readIndex(Path root) {
        Path index = root.resolve(CHANNELS_FILE);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String nameFor(List<String> rows, String id) {
        for (String row : rows) {
            String[] parts = Tsv.split(row, 3);
            if (parts[0].equals(id)) {
                return parts[1].isBlank() ? id : parts[1];
            }
        }
        return id;
    }

    private static String name(Channel channel) {
        String name = channel.getName();
        return name == null || name.isBlank() ? "(unnamed)" : name;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
