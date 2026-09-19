/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Somewhere a backup archive can be put, and taken back out of.
 *
 * <p>Five operations, and every one of them is needed by something the plugin offers:
 * {@link #put} takes the backup, {@link #list} draws the page and finds what retention
 * would remove, {@link #fetch} is how a restore gets its archive back, {@link #delete} is
 * retention actually removing it, and {@link #probe} is the button that says whether any of
 * the above will work before the first scheduled run proves it at three in the morning.
 *
 * <p>The contract is deliberately file-shaped rather than stream-shaped. Every
 * implementation here needs the length up front (S3 and Azure sign it, SFTP and FTP report
 * progress against it) and the archive is already a file on disk by the time it is
 * uploaded, so handing over a {@link Path} costs nothing and removes the "how long is this
 * stream" question from five classes at once.
 *
 * <h2>Names</h2>
 *
 * <p>A {@code name} is a single file name -- {@code oie-backup-20260919T020000Z.zip} -- and
 * never a path. Each implementation joins it to its own configured prefix or directory.
 * That is what lets a backup taken to S3 be restored from SFTP by copying one file, and it
 * is also the containment rule: {@link Destinations#requireSimpleName} refuses anything
 * with a slash, a backslash or a {@code ..} in it, so a manifest read out of an untrusted
 * archive cannot talk any of these classes into writing outside their configured location.
 */
public interface Destination extends Closeable {

    /** One archive, as the destination reports it. */
    final class RemoteFile {
        public final String name;
        public final long size;
        /** Null when the destination does not report one; the UI then shows the name only. */
        public final Instant modified;

        public RemoteFile(String name, long size, Instant modified) {
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    /**
     * What this destination is, in one line, for the console and the log.
     *
     * <p>Must never include a credential. These strings end up in the history table, in
     * {@code server.log} and on a page somebody screenshots into a ticket.
     */
    String describe();

    /** Uploads {@code file}, replacing any archive already stored under {@code name}. */
    void put(String name, Path file) throws IOException;

    /**
     * The archives this destination holds, newest first where the destination says.
     *
     * <p>Only files this plugin would have written are returned -- see
     * {@link Destinations#isArchiveName} -- so pointing a backup at a directory that
     * already has other things in it cannot end with retention deleting them.
     */
    List<RemoteFile> list() throws IOException;

    /** Downloads {@code name} to {@code target}, which is overwritten. */
    void fetch(String name, Path target) throws IOException;

    void delete(String name) throws IOException;

    /**
     * Proves the settings work, without waiting for a backup to do it.
     *
     * <p>Writes a small marker, reads the listing back and removes the marker again. The
     * removal is allowed to fail without failing the probe: write-once storage and buckets
     * with a deny-delete policy are a good way to hold backups, not a misconfiguration, and
     * refusing to accept one would be this plugin overruling a decision that is properly the
     * deployment's. What it does instead is say so, because retention pruning will not work
     * there and finding that out from a warning beats finding it out from a bill.
     *
     * @return a sentence describing what happened, shown verbatim in the console
     */
    String probe() throws IOException;

    @Override
    void close() throws IOException;
}
