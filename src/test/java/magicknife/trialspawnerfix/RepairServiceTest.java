package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RepairServiceTest {
    private final Logger logger = Logger.getLogger("repair-test");
    private final AuditRecord.Context context = new AuditRecord.Context(UUID.randomUUID(), "fixture", 1, 64, 1,
            1, 0, "test", null);

    private PluginSettings settings(CooldownPolicy.Strategy strategy) {
        return new PluginSettings(strategy, Set.of(), 200, 1, true, 8, 4096, PluginSettings.Budget.defaults());
    }

    private static final class MemorySink implements AuditJournal.Sink {
        private final List<AuditRecord> records = new ArrayList<>();
        @Override
        public synchronized void write(AuditRecord record) {
            records.add(record);
        }
        @Override
        public void close() {
        }
    }

    private static final class Target implements RepairService.Target, RepairService.Snapshot {
        private CooldownPolicy.Input input;
        private boolean available = true;
        private boolean replaced;
        private boolean update = true;
        private boolean mismatch;
        private boolean throwsOnWrite;
        private boolean throwsOnReadback;
        private boolean missingReadback;
        private int writes;
        private long end;

        Target(CooldownPolicy.Stage stage, long end) {
            input = new CooldownPolicy.Input(true, stage, true, 1000, end, 72_000, 200,
                    CooldownPolicy.Strategy.RESTART_COOLDOWN, 1);
            this.end = end;
        }
        @Override
        public boolean available() {
            return available;
        }
        @Override
        public RepairService.Snapshot read(PluginSettings settings) {
            if (replaced) {
                return null;
            }
            input = new CooldownPolicy.Input(settings.enables(contextWorld()), input.stage(), input.ominous(),
                    input.gameTime(), end, input.cooldownLength(), settings.margin(), settings.strategy(), settings.generation());
            return this;
        }
        private UUID contextWorld() {
            return new UUID(0, 1);
        }
        @Override
        public CooldownPolicy.Input input() {
            return input;
        }
        @Override
        public boolean writeEnd(long end) {
            writes++;
            if (throwsOnWrite) {
                throw new IllegalStateException("fixture write failed");
            }
            if (update) {
                this.end = end;
            }
            return update;
        }
        @Override
        public Long readEnd() {
            if (throwsOnReadback) {
                throw new IllegalStateException("fixture readback failed");
            }
            if (missingReadback) {
                return null;
            }
            return mismatch ? end + 1 : end;
        }
    }

    @Test
    void rereadsCurrentStageReplacementAndUnloadInsteadOfTrustingCandidate() {
        var service = new RepairService(() -> settings(CooldownPolicy.Strategy.RESTART_COOLDOWN), null, logger);
        var combat = new Target(CooldownPolicy.Stage.ACTIVE, 999_999);
        assertEquals(RepairService.Result.SKIPPED, service.check(combat, context));
        var unloaded = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
        unloaded.available = false;
        assertEquals(RepairService.Result.UNAVAILABLE, service.check(unloaded, context));
        var replaced = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
        replaced.replaced = true;
        assertEquals(RepairService.Result.REPLACED, service.check(replaced, context));
        assertEquals(0, combat.writes + unloaded.writes + replaced.writes);
    }

    @Test
    void readOnlyAndUnavailableAuditNeverWrite() {
        var target = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
        assertEquals(RepairService.Result.REPORTED, new RepairService(
                () -> settings(CooldownPolicy.Strategy.REPORT_ONLY), null, logger).check(target, context));
        assertEquals(RepairService.Result.AUDIT_UNAVAILABLE, new RepairService(
                () -> settings(CooldownPolicy.Strategy.RESTART_COOLDOWN), null, logger).check(target, context));
        assertEquals(0, target.writes);
    }

    @Test
    void onlyCountsVerifiedWritesAndAuditsFailuresIncludingExceptions() {
        var current = settings(CooldownPolicy.Strategy.RESTART_COOLDOWN);
        var sink = new MemorySink();
        try (var journal = new AuditJournal(32, sink, logger, "1", "961")) {
            var service = new RepairService(() -> current, journal, logger);
            for (var expected : new RepairService.Result[]{RepairService.Result.REPAIRED,
                    RepairService.Result.UPDATE_FAILED, RepairService.Result.READBACK_MISMATCH, RepairService.Result.ERROR}) {
                var target = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
                target.update = expected != RepairService.Result.UPDATE_FAILED;
                target.mismatch = expected == RepairService.Result.READBACK_MISMATCH;
                target.throwsOnWrite = expected == RepairService.Result.ERROR;
                assertEquals(expected, service.check(target, context));
                assertEquals(1, target.writes);
                if (expected == RepairService.Result.REPAIRED) {
                    assertEquals(73_000, target.end);
                    assertEquals(RepairService.Result.SKIPPED, service.check(target, context));
                    assertEquals(1, target.writes);
                }
            }
        }
        assertEquals(8, sink.records.size());
        assertEquals(1, sink.records.stream().filter(record -> record.result().equals("repaired")).count());
        for (AuditRecord result : sink.records.stream().filter(record -> record.event().equals("repair_result")).toList()) {
            assertTrue(sink.records.stream().anyMatch(attempt -> attempt.event().equals("repair_attempt")
                    && attempt.eventId() == result.attemptId()));
        }
    }

    @Test
    void configOrLifecycleChangeBeforeWriteCancelsReservedAttempt() {
        var original = settings(CooldownPolicy.Strategy.RESTART_COOLDOWN);
        var current = new AtomicReference<>(original);
        var sink = new MemorySink();
        var target = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
        try (var journal = new AuditJournal(8, sink, logger, "1", "961")) {
            var service = new RepairService(() -> current.getAndSet(null), journal, logger);
            assertEquals(RepairService.Result.CONFIG_CHANGED, service.check(target, context));
        }
        assertEquals(0, target.writes);
        assertEquals("config_changed", sink.records.getLast().reason());
    }

    @Test
    void missingOrThrowingReadbackNeverReportsSuccess() {
        var current = settings(CooldownPolicy.Strategy.RESTART_COOLDOWN);
        var sink = new MemorySink();
        try (var journal = new AuditJournal(8, sink, logger, "1", "961")) {
            var service = new RepairService(() -> current, journal, logger);
            var missing = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
            missing.missingReadback = true;
            assertEquals(RepairService.Result.READBACK_MISMATCH, service.check(missing, context));
            var throwing = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
            throwing.throwsOnReadback = true;
            assertEquals(RepairService.Result.ERROR, service.check(throwing, context));
        }
        assertEquals(2, sink.records.stream().filter(record -> record.result().equals("failed")).count());
        assertFalse(sink.records.stream().anyMatch(record -> record.result().equals("repaired")));
    }

    @Test
    void fullOrFailedAuditRejectsRepairWithoutWriting() throws Exception {
        var current = settings(CooldownPolicy.Strategy.RESTART_COOLDOWN);
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
                throw new IOException("fixture disk failure");
            }
            @Override
            public void close() {
            }
        };
        try (var journal = new AuditJournal(2, sink, logger, "1", "961")) {
            var target = new Target(CooldownPolicy.Stage.COOLDOWN, 999_999);
            var service = new RepairService(() -> current, journal, logger);
            var input = target.input();
            journal.report(context, input, CooldownPolicy.evaluate(input));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            journal.report(context, input, CooldownPolicy.evaluate(input));
            assertEquals(RepairService.Result.AUDIT_UNAVAILABLE, service.check(target, context));
            release.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (journal.healthy()) {
                    Thread.sleep(1);
                }
            });
            assertEquals(RepairService.Result.AUDIT_UNAVAILABLE, service.check(target, context));
            assertEquals(0, target.writes);
        } finally {
            release.countDown();
        }
    }
}
