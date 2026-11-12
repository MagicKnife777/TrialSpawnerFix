package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditJournalTest {
    @TempDir
    Path directory;
    private final Logger logger = Logger.getLogger("audit-test");
    private final AuditRecord.Context context = new AuditRecord.Context(UUID.randomUUID(), "世界\"\\\n", 1, 64, 2,
            9, 0, "test", null);
    private final CooldownPolicy.Input input = new CooldownPolicy.Input(true, CooldownPolicy.Stage.COOLDOWN, true,
            100, 999_999, 100, 200, CooldownPolicy.Strategy.RESTART_COOLDOWN, 1);
    private final CooldownPolicy.Decision decision = CooldownPolicy.evaluate(input);

    @Test
    void resultSlotCannotBeConsumedByDiagnosticsDuringSlowDisk() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var sink = new AuditJournal.Sink() {
            @Override
            public void write(AuditRecord record) throws IOException {
                writing.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("fixture timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            @Override
            public void close() {
            }
        };
        try (var journal = new AuditJournal(2, sink, logger, "1", "961")) {
            var reservation = journal.reserve(context, input, decision);
            assertNotNull(reservation);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            journal.report(context, input, decision);
            journal.report(context, input, decision);
            assertEquals(1, journal.dropped());
            assertNull(journal.reserve(context, input, decision));
            reservation.finish("repaired", "repaired", 1);
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedWriterPermanentlyRejectsFurtherRepairs() {
        var sink = new AuditJournal.Sink() {
            @Override
            public void write(AuditRecord record) throws IOException {
                throw new IOException("fixture disk failure");
            }
            @Override
            public void close() {
            }
        };
        try (var journal = new AuditJournal(8, sink, logger, "1", "961")) {
            var reservation = journal.reserve(context, input, decision);
            assertNotNull(reservation);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (journal.healthy()) {
                    Thread.sleep(1);
                }
            });
            assertNull(journal.reserve(context, input, decision));
            reservation.finish("audit_unavailable", "failed", 1);
            assertTrue(journal.dropped() >= 2);
        }
    }

    @Test
    void writesValidUtf8CorrelatedJsonAndDrainsOnShutdown() throws Exception {
        try (var journal = new AuditJournal(8, new AuditFiles(directory), logger, "1", "961")) {
            var reservation = journal.reserve(context, input, decision);
            assertNotNull(reservation);
            reservation.finish("repaired", "repaired", 12);
        }
        try (var files = Files.list(directory.resolve("audit"))) {
            var lines = Files.readAllLines(files.findFirst().orElseThrow());
            assertEquals(2, lines.size());
            var attempt = JsonParser.parseString(lines.getFirst()).getAsJsonObject();
            var result = JsonParser.parseString(lines.getLast()).getAsJsonObject();
            assertEquals(context.worldName(), attempt.get("worldName").getAsString());
            assertEquals(attempt.get("eventId"), result.get("attemptId"));
            assertEquals("repair_result", result.get("event").getAsString());
            assertEquals("repaired", result.get("result").getAsString());
        }
    }

    @Test
    void rotationAndRetentionOnlyDeleteOwnedAuditFiles() throws Exception {
        Path audit = Files.createDirectories(directory.resolve("audit"));
        Path unrelated = audit.resolve("keep.jsonl");
        Files.writeString(unrelated, "用户文件");
        Path old = audit.resolve("trialspawnerfix-old.jsonl");
        Files.writeString(old, "old");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(15))));
        try (var sink = new AuditFiles(directory, 1500, 3000)) {
            for (int i = 0; i < 6; i++) {
                sink.write(AuditRecord.create(UUID.randomUUID(), i, 0, "1", "961", context, input, decision,
                        "suspected", "suspected", "report-only", 0));
            }
        }
        assertFalse(Files.exists(old));
        assertEquals("用户文件", Files.readString(unrelated));
        try (var files = Files.list(audit)) {
            var owned = files.filter(path -> path.getFileName().toString().startsWith("trialspawnerfix-")).toList();
            assertTrue(owned.size() > 1);
            long total = 0;
            for (Path file : owned) {
                assertTrue(Files.size(file) <= 1500);
                total += Files.size(file);
            }
            assertTrue(total <= 3000);
        }
    }

    @Test
    void shutdownReturnsWithinBoundWhenWriterIsBlocked() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        var sink = new AuditJournal.Sink() {
            @Override
            public void write(AuditRecord record) throws IOException {
                writing.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            @Override
            public void close() {
                closed.countDown();
            }
        };
        var journal = new AuditJournal(2, sink, logger, "1", "961");
        try {
            journal.report(context, input, decision);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3), journal::close);
            assertFalse(journal.healthy());
            assertNull(journal.reserve(context, input, decision));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            journal.close();
        }
    }

    @Test
    void recoveryRequiresARealWriteBeforePublishingHealthyAudit() throws Exception {
        try (var journal = new AuditJournal(2, new AuditFiles(directory), logger, "1", "961")) {
            journal.verifyReady();
            assertTrue(journal.healthy());
        }
        try (var files = Files.list(directory.resolve("audit"))) {
            var first = Files.readAllLines(files.findFirst().orElseThrow()).getFirst();
            assertEquals("audit_ready", JsonParser.parseString(first).getAsJsonObject().get("event").getAsString());
        }
    }
}
