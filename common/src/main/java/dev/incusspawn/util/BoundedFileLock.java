package dev.incusspawn.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** A stable-inode advisory file lock with bounded cross-process and same-JVM waiting. */
public final class BoundedFileLock implements AutoCloseable {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<>();
    private static final long RETRY_MILLIS = 100;

    private final FileChannel channel;
    private final FileLock fileLock;
    private final ReentrantLock jvmLock;

    private BoundedFileLock(FileChannel channel, FileLock fileLock, ReentrantLock jvmLock) {
        this.channel = channel;
        this.fileLock = fileLock;
        this.jvmLock = jvmLock;
    }

    public static BoundedFileLock acquire(
            Path file, Duration timeout, String waitMessage, String timeoutMessage)
            throws IOException {
        var normalized = file.toAbsolutePath().normalize();
        ensureProtectedLockFile(normalized);
        var deadline = System.nanoTime() + timeout.toNanos();
        var jvmLock = JVM_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock());
        boolean announcedWait = false;
        boolean acquiredJvm = jvmLock.tryLock();
        if (!acquiredJvm) {
            if (waitMessage != null && !waitMessage.isBlank()) {
                System.err.println(waitMessage);
                announcedWait = true;
            }
            try {
                acquiredJvm = jvmLock.tryLock(Math.max(0, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for lock " + normalized, e);
            }
        }
        if (!acquiredJvm) throw new IOException(timeoutMessage);

        FileChannel channel = null;
        try {
            channel = FileChannel.open(normalized, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            while (true) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock != null) return new BoundedFileLock(channel, lock, jvmLock);
                if (!announcedWait && waitMessage != null && !waitMessage.isBlank()) {
                    System.err.println(waitMessage);
                    announcedWait = true;
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException(timeoutMessage);
                try {
                    Thread.sleep(Math.min(RETRY_MILLIS,
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for lock " + normalized, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            } finally {
                jvmLock.unlock();
            }
            throw e;
        }
    }

    private static void ensureProtectedLockFile(Path file) throws IOException {
        var directory = file.getParent();
        Files.createDirectories(directory,
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        requirePhysical(directory, true);
        requirePermissions(directory, DIRECTORY_PERMISSIONS);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (FileAlreadyExistsException ignored) {
            // Atomic first-use creation: validate the winner below.
        }
        requirePhysical(file, false);
        requirePermissions(file, FILE_PERMISSIONS);
        if (!Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("lock file owner differs from lock directory owner: " + file);
        }
    }

    private static void requirePhysical(Path path, boolean directory) throws IOException {
        var correctType = directory
                ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!correctType || Files.isSymbolicLink(path)) {
            throw new IOException("lock path must be a physical "
                    + (directory ? "directory: " : "file: ") + path);
        }
    }

    private static void requirePermissions(Path path, Set<PosixFilePermission> expected)
            throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null || !view.readAttributes().permissions().equals(expected)) {
            throw new IOException("lock path has unsafe permissions: " + path);
        }
    }

    @Override
    public void close() {
        IOException failure = null;
        try {
            fileLock.release();
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw new UncheckedIOException(failure);
    }
}
