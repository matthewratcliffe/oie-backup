/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory the engine can write to.
 *
 * <p>This is also the "network path" destination, and that is a deliberate simplification
 * rather than a gap. SMB and NFS are mounted by the operating system or by the container
 * runtime -- {@code volumes:} in compose, a {@code PersistentVolumeClaim} in Kubernetes, a
 * {@code mount -t cifs} on a host -- and once mounted they are a directory. Speaking SMB
 * from inside the JVM instead would mean bundling a client, holding the share credential in
 * the engine's configuration table, and getting a protocol famous for its dialect
 * negotiation right, all to arrive at the same directory the platform can hand over
 * already. On Windows a UNC path works here directly, because {@link Path} accepts one.
 *
 * <p>The failure this design does have is worth naming: when the mount is not there, the
 * path is an ordinary empty directory on the container's own filesystem, and backups go
 * into it happily until the container is replaced and they disappear with it. So
 * {@link #probe} reports the filesystem's free space and total size, which is how that
 * shows up -- a "network path" sitting on the same 30 GB overlay as everything else is
 * visible at a glance, and a share is not.
 */
final class LocalDestination implements Destination {

    private final Path directory;
    /** Overrides the description for the "keep on this engine" case. */
    private final String label;

    LocalDestination(Path directory, String label) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.label = label;
        Files.createDirectories(this.directory);
    }

    @Override
    public String describe() {
        return label != null ? label + " (" + directory + ")" : directory.toString();
    }

    @Override
    public void put(String name, Path file) throws IOException {
        Path target = resolve(name);
        /*
         * Written beside the target and moved into place. A reader listing this directory
         * during a slow copy would otherwise see a truncated archive under its final name,
         * and the one reader that matters here is the next run's retention pass, which
         * counts what it finds. ATOMIC_MOVE is requested and its absence tolerated: on a
         * CIFS or NFS mount it is not always supported, and a plain move is still far
         * better than a copy.
         */
        Path temp = directory.resolve(name + ".part");
        Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException | java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !Destinations.isArchiveName(name)) {
                    continue;
                }
                out.add(new RemoteFile(name, Files.size(p),
                    Files.getLastModifiedTime(p).toInstant()));
            }
        }
        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        Files.copy(resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            Files.delete(resolve(name));
        } catch (NoSuchFileException e) {
            // Already gone. Retention asking twice is not a failure.
        }
    }

    @Override
    public String probe() throws IOException {
        Path marker = directory.resolve(".oie-backup-probe");
        Files.writeString(marker, "probe\n", StandardCharsets.UTF_8);
        long free = Files.getFileStore(directory).getUsableSpace();
        long total = Files.getFileStore(directory).getTotalSpace();
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            return "Wrote to " + directory + ", but could not remove the probe file: "
                + e.getMessage() + ". Retention will not be able to remove old archives.";
        }
        return "Wrote and removed a probe file in " + directory + ". "
            + Destinations.bytes(free) + " free of " + Destinations.bytes(total)
            + " on the filesystem holding it.";
    }

    @Override
    public void close() {
        // Nothing held open.
    }

    private Path resolve(String name) {
        return directory.resolve(Destinations.requireSimpleName(name));
    }
}
