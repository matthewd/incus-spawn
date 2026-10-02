package dev.incusspawn.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedFileLockTest {

    @TempDir Path tempDir;

    @Test
    void firstUseCreationAndSameJvmContentionAreSerialized() throws Exception {
        var lockFile = tempDir.resolve("locks/pool-management.lock");
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (var ignored = BoundedFileLock.acquire(lockFile, Duration.ofSeconds(5),
                            null, "timed out")) {
                        var current = active.incrementAndGet();
                        maximumActive.accumulateAndGet(current, Math::max);
                        Thread.sleep(10);
                        active.decrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, maximumActive.get());
        assertTrue(Files.isRegularFile(lockFile));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(lockFile));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(lockFile.getParent()));
    }

    @Test
    void lockInodePersistsAfterReleaseAndWaitIsBounded() throws Exception {
        var lockFile = tempDir.resolve("locks/vm.lock");
        try (var held = BoundedFileLock.acquire(lockFile, Duration.ofSeconds(1),
                null, "holder timed out")) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var contender = executor.submit(() -> assertThrows(java.io.IOException.class,
                        () -> BoundedFileLock.acquire(lockFile, Duration.ofMillis(100),
                                null, "bounded timeout")));
                var failure = contender.get(2, TimeUnit.SECONDS);
                assertEquals("bounded timeout", failure.getMessage());
                assertTrue(Files.exists(lockFile), "a held lock inode must remain linked");
            } finally {
                executor.shutdownNow();
            }
        }

        assertTrue(Files.exists(lockFile), "stable lock inodes persist after release");
    }
}
