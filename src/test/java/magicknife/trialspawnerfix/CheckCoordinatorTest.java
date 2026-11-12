package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class CheckCoordinatorTest {
    private final UUID world = UUID.randomUUID();
    private final Logger logger = Logger.getLogger("coordinator-test");

    private CheckCoordinator.Key key(int x) {
        return new CheckCoordinator.Key(world, x, 0);
    }

    private static final class Platform implements CheckCoordinator.Platform {
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        @Override
        public CheckCoordinator.Cancellation schedule(CheckCoordinator.Key key, Runnable callback) {
            callbacks.add(callback);
            return () -> callbacks.remove(callback);
        }
        void runAll() {
            while (!callbacks.isEmpty()) {
                callbacks.remove().run();
            }
        }
    }

    private CheckCoordinator create(int pending, int inFlight, int dispatch, Platform platform, CheckCoordinator.Work work) {
        return new CheckCoordinator(() -> new PluginSettings.Budget(pending, inFlight, dispatch, 1, 2, 10),
                platform, work, logger);
    }

    @Test
    void deduplicatesSourcesAndCountsSubmittedAndContinuationAgainstCapacity() {
        var platform = new Platform();
        var seen = new ArrayList<CheckCoordinator.Ticket>();
        var coordinator = create(2, 1, 1, platform, (ticket, cursor, valid) -> {
            seen.add(ticket);
            return cursor == null ? new CheckCoordinator.Cursor(List.of(), 1) : null;
        });
        assertTrue(coordinator.submit(key(0), CheckCoordinator.Source.STARTUP, 1));
        assertTrue(coordinator.submit(key(0), CheckCoordinator.Source.CHUNK_LOAD, 0));
        assertTrue(coordinator.submit(key(1), CheckCoordinator.Source.STARTUP, 1));
        assertFalse(coordinator.submit(key(2), CheckCoordinator.Source.CHUNK_LOAD, 0));
        coordinator.tick();
        assertEquals(1, platform.callbacks.size());
        assertEquals(2, coordinator.stats().pending());
        assertEquals(1, coordinator.stats().inFlight());
        platform.runAll();
        assertEquals(Set.of(CheckCoordinator.Source.STARTUP, CheckCoordinator.Source.CHUNK_LOAD), seen.getFirst().sources());
        assertEquals(2, coordinator.stats().pending());
        assertEquals(0, coordinator.stats().inFlight());
        assertTrue(platform.callbacks.isEmpty());
        for (int i = 0; i < 5; i++) {
            coordinator.tick();
            platform.runAll();
        }
        assertEquals(4, seen.size());
        assertEquals(0, coordinator.stats().pending());
        assertEquals(2, coordinator.stats().highWater());
        assertEquals(Set.of(world), coordinator.stats().gaps());
    }

    @Test
    void requestDuringExecutionOrContinuationCausesOneFreshPass() {
        var platform = new Platform();
        var seen = new ArrayList<CheckCoordinator.Cursor>();
        var coordinator = create(1, 1, 1, platform, (ticket, cursor, valid) -> {
            seen.add(cursor);
            return cursor == null ? new CheckCoordinator.Cursor(List.of(), 1) : null;
        });
        coordinator.submit(key(0), CheckCoordinator.Source.STARTUP, 1);
        coordinator.tick();
        platform.runAll();
        for (int i = 0; i < 10; i++) {
            coordinator.submit(key(0), CheckCoordinator.Source.TIME_ROLLBACK, 2);
        }
        for (int i = 0; i < 3; i++) {
            coordinator.tick();
            platform.runAll();
        }
        assertEquals(4, seen.size());
        assertNull(seen.get(0));
        assertNotNull(seen.get(1));
        assertNull(seen.get(2));
        assertNotNull(seen.get(3));
        assertEquals(0, coordinator.stats().pending());
    }

    @Test
    void backgroundReceivesShareAndDispatchNeverExceedsLimits() {
        var platform = new Platform();
        var order = new ArrayList<Integer>();
        var coordinator = create(20, 3, 2, platform, (ticket, cursor, valid) -> {
            order.add(ticket.key().x());
            return null;
        });
        coordinator.submit(key(99), CheckCoordinator.Source.STARTUP, 1);
        for (int i = 0; i < 10; i++) {
            coordinator.submit(key(i), CheckCoordinator.Source.CHUNK_LOAD, 0);
        }
        coordinator.tick();
        assertEquals(2, platform.callbacks.size());
        coordinator.tick();
        assertEquals(3, platform.callbacks.size());
        platform.runAll();
        coordinator.tick();
        platform.runAll();
        assertTrue(order.indexOf(99) <= 3);
    }

    @Test
    void scanCursorKeepsUnsubmittedPageAndPauseEndsOnlyAfterCoverage() {
        var platform = new Platform();
        var order = new ArrayList<Integer>();
        var coordinator = create(1, 1, 1, platform, (ticket, cursor, valid) -> {
            order.add(ticket.key().x());
            return null;
        });
        var cursor = new ScanCursor(List.of(key(0), key(1), key(2)));
        coordinator.pauseWorld(world);
        assertFalse(coordinator.automaticAllowed(world));
        assertFalse(cursor.feed(coordinator, CheckCoordinator.Source.RECOVERY, 1, 3));
        assertEquals(2, cursor.remaining());
        for (int i = 0; i < 3; i++) {
            coordinator.tick();
            platform.runAll();
            cursor.feed(coordinator, CheckCoordinator.Source.RECOVERY, 1, 3);
        }
        assertEquals(List.of(0, 1, 2), order);
        assertEquals(0, cursor.remaining());
        coordinator.covered(world);
        assertTrue(coordinator.automaticAllowed(world));
        assertTrue(coordinator.stats().gaps().isEmpty());
    }

    @Test
    void unloadReloadAndFailedCallbacksReleaseSlotsWithoutStaleWrites() {
        var platform = new Platform();
        var seen = new ArrayList<Long>();
        var coordinator = create(2, 2, 2, platform, (ticket, cursor, valid) -> {
            seen.add(ticket.generation());
            if (ticket.key().x() == 1) {
                throw new IllegalStateException("fixture read failure");
            }
            return null;
        });
        coordinator.submit(key(0), CheckCoordinator.Source.CHUNK_LOAD, 0);
        coordinator.tick();
        Runnable stale = platform.callbacks.getFirst();
        coordinator.invalidate(key(0));
        coordinator.submit(key(0), CheckCoordinator.Source.CHUNK_LOAD, 0);
        coordinator.submit(key(1), CheckCoordinator.Source.CHUNK_LOAD, 0);
        stale.run();
        coordinator.tick();
        platform.runAll();
        assertEquals(2, seen.size());
        assertTrue(seen.getFirst() > 1);
        assertEquals(0, coordinator.stats().pending());
        assertEquals(0, coordinator.stats().inFlight());
    }

    @Test
    void stopInvalidatesRunningWorkAndWaitsForItBeforeReturning() throws Exception {
        var platform = new Platform();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var coordinator = create(1, 1, 1, platform, (ticket, cursor, valid) -> {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
                assertFalse(valid.getAsBoolean());
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            coordinator.submit(key(0), CheckCoordinator.Source.CHUNK_LOAD, 0);
            coordinator.tick();
            var running = executor.submit(platform.callbacks.remove());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var stopping = executor.submit(coordinator::stop);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                while (coordinator.offer(key(0), CheckCoordinator.Source.CHUNK_LOAD, 0)) {
                    Thread.yield();
                }
            });
            assertFalse(stopping.isDone());
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            stopping.get(2, TimeUnit.SECONDS);
            assertEquals(0, coordinator.stats().pending());
            assertEquals(0, coordinator.stats().inFlight());
        } finally {
            release.countDown();
        }
    }
}
