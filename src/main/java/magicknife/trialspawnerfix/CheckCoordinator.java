package magicknife.trialspawnerfix;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class CheckCoordinator {
    record Key(UUID worldId, int x, int z) {
    }

    enum Source {
        CHUNK_LOAD, ADMIN, STARTUP, TIME_ROLLBACK, RECOVERY;

        boolean foreground() {
            return this == CHUNK_LOAD || this == ADMIN;
        }
    }

    record Point(Position position, CooldownPolicy.Strategy strategy, String actor, Consumer<String> completed) {
    }

    record Ticket(Key key, long generation, Set<Source> sources, long scanId, Point point, String actor) {
    }

    record Position(int x, int y, int z) {
    }

    record Cursor(List<Position> positions, int offset) {
    }

    interface Cancellation {
        boolean cancel();
    }

    interface Platform {
        Cancellation schedule(Key key, Runnable callback);
    }

    interface Work {
        Cursor run(Ticket ticket, Cursor cursor, BooleanSupplier valid);
    }

    record Stats(int pending, int inFlight, int highWater, long rejected, Set<UUID> gaps, Set<UUID> paused) {
    }

    private static final class Entry {
        private final Key key;
        private final long generation;
        private final EnumSet<Source> sources = EnumSet.noneOf(Source.class);
        private long scanId;
        private String actor;
        private boolean batch;
        private final ArrayDeque<Point> points = new ArrayDeque<>();
        private final Map<Long, Consumer<Boolean>> completions = new HashMap<>();
        private Cursor cursor;
        private Cancellation task;
        private boolean submitted;
        private boolean running;
        private boolean started;
        private boolean rerun;
        private boolean invalid;
        private boolean released;
        private long readyTick;

        private Entry(Key key, long generation) {
            this.key = key;
            this.generation = generation;
        }

        private boolean foreground() {
            return sources.stream().anyMatch(Source::foreground);
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();
    private final Set<Entry> occupied = new HashSet<>();
    private final ArrayDeque<Entry> foreground = new ArrayDeque<>();
    private final ArrayDeque<Entry> background = new ArrayDeque<>();
    private final Set<UUID> gaps = new HashSet<>();
    private final Set<UUID> paused = new HashSet<>();
    private final Map<UUID, Long> gapVersions = new HashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final Supplier<PluginSettings.Budget> budget;
    private final Platform platform;
    private final Work work;
    private final DiagnosticLog diagnostics;
    private boolean active = true;
    private long generations;
    private long tick;
    private int turn;
    private int inFlight;
    private int highWater;
    private long rejected;
    private int pointCount;

    CheckCoordinator(Supplier<PluginSettings.Budget> budget, Platform platform, Work work, Logger logger) {
        this(budget, platform, work, new DiagnosticLog(logger));
    }

    CheckCoordinator(Supplier<PluginSettings.Budget> budget, Platform platform, Work work, DiagnosticLog diagnostics) {
        this.budget = budget;
        this.platform = platform;
        this.work = work;
        this.diagnostics = diagnostics;
    }

    synchronized boolean submit(Key key, Source source, long scanId) {
        boolean accepted = offer(key, source, scanId);
        if (!accepted && active) {
            rejected++;
            markGap(key.worldId());
            diagnostics.report(key.worldId(), "queue_overloaded", "检查容量不足，已标记覆盖缺口。", null);
        }
        return accepted;
    }

    synchronized boolean offer(Key key, Source source, long scanId) {
        return offer(key, source, scanId, null);
    }

    synchronized boolean offer(Key key, Source source, long scanId, Consumer<Boolean> completed) {
        return offer(key, source, scanId, null, completed);
    }

    synchronized boolean offer(Key key, Source source, long scanId, String actor, Consumer<Boolean> completed) {
        if (!active) {
            return false;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            if (occupied.size() >= budget.get().pendingChunks()) {
                return false;
            }
            entry = new Entry(key, ++generations);
            entries.put(key, entry);
            occupied.add(entry);
            highWater = Math.max(highWater, occupied.size());
            entry.sources.add(source);
            enqueue(entry);
        } else {
            boolean promote = !entry.foreground() && source.foreground() && !entry.submitted;
            entry.sources.add(source);
            if (promote) {
                background.remove(entry);
                foreground.add(entry);
            }
            entry.rerun |= entry.started;
        }
        entry.scanId = Math.max(entry.scanId, scanId);
        if (actor != null) {
            entry.actor = actor;
        }
        entry.batch = true;
        if (completed != null) {
            entry.completions.put(scanId, completed);
        }
        return true;
    }

    synchronized boolean point(Key key, Point point) {
        if (!active || pointCount >= budget.get().pendingChunks()) {
            return false;
        }
        Entry entry = entries.get(key);
        boolean batch = entry != null && entry.batch;
        if (!offer(key, Source.ADMIN, 0)) {
            rejected++;
            return false;
        }
        entry = entries.get(key);
        entry.batch = batch;
        entry.points.add(point);
        pointCount++;
        return true;
    }

    void tick() {
        PluginSettings.Budget limits = budget.get();
        synchronized (this) {
            tick++;
        }
        for (int dispatched = 0; dispatched < limits.dispatchPerTick(); dispatched++) {
            Entry entry;
            Ticket ticket;
            synchronized (this) {
                if (!active || inFlight >= limits.inFlightChunks()) {
                    return;
                }
                entry = next();
                if (entry == null) {
                    return;
                }
                entry.submitted = true;
                inFlight++;
                ticket = new Ticket(entry.key, entry.generation, Set.copyOf(entry.sources), entry.scanId, null, entry.actor);
            }
            try {
                Cancellation task = platform.schedule(entry.key, () -> execute(entry, ticket));
                synchronized (this) {
                    if (!entry.released && entry.submitted) {
                        entry.task = task;
                        if (entry.invalid && !entry.running && task.cancel()) {
                            release(entry);
                        }
                    } else {
                        task.cancel();
                    }
                }
            } catch (RuntimeException exception) {
                synchronized (this) {
                    markGap(entry.key.worldId());
                    entry.invalid = true;
                    release(entry);
                }
                diagnostics.report(ticket.key().worldId(), "schedule_failed", ticket.toString(), exception);
            }
        }
    }

    private Entry next() {
        // 三次前台、一次后台；空队列的份额由另一队列使用。
        boolean preferBackground = turn++ % 4 == 3;
        Entry entry = poll(preferBackground ? background : foreground);
        return entry != null ? entry : poll(preferBackground ? foreground : background);
    }

    private Entry poll(ArrayDeque<Entry> queue) {
        int count = queue.size();
        for (int i = 0; i < count; i++) {
            Entry entry = queue.remove();
            if (entry.readyTick <= tick) {
                return entry;
            }
            queue.add(entry);
        }
        return null;
    }

    private void execute(Entry entry, Ticket ticket) {
        lifecycle.readLock().lock();
        Cursor continuation = null;
        boolean failed = false;
        Point point = null;
        try {
            synchronized (this) {
                if (!valid(entry)) {
                    return;
                }
                entry.running = true;
                point = entry.points.poll();
                entry.started |= point == null;
                ticket = new Ticket(entry.key, entry.generation, Set.copyOf(entry.sources), entry.scanId, point, entry.actor);
            }
            continuation = work.run(ticket, point == null ? entry.cursor : null, () -> valid(entry));
        } catch (RuntimeException exception) {
            failed = true;
            diagnostics.report(ticket.key().worldId(), "chunk_failed", ticket.toString(), exception);
            if (point != null) {
                point.completed().accept("ERROR");
            }
        } finally {
            synchronized (this) {
                if (point != null) {
                    pointCount--;
                }
                if (!valid(entry) || failed) {
                    entry.invalid |= failed;
                    release(entry);
                } else {
                    entry.running = false;
                    entry.submitted = false;
                    entry.task = null;
                    inFlight--;
                    if (point == null) {
                        entry.cursor = continuation;
                        if (continuation == null) {
                            entry.batch = entry.rerun;
                            entry.rerun = false;
                            entry.started = false;
                        }
                    }
                    if (!entry.batch && entry.points.isEmpty()) {
                        release(entry);
                    } else {
                        entry.readyTick = tick + 1;
                        enqueue(entry);
                    }
                }
            }
            lifecycle.readLock().unlock();
        }
    }

    private synchronized boolean valid(Entry entry) {
        return active && !entry.invalid && !entry.released && entries.get(entry.key) == entry;
    }

    private void enqueue(Entry entry) {
        (entry.foreground() ? foreground : background).add(entry);
    }

    private void release(Entry entry) {
        if (entry.released) {
            return;
        }
        entry.released = true;
        entries.remove(entry.key, entry);
        occupied.remove(entry);
        foreground.remove(entry);
        background.remove(entry);
        if (entry.submitted) {
            inFlight--;
        }
        for (Point point : entry.points) {
            pointCount--;
            point.completed().accept("UNAVAILABLE");
        }
        for (Consumer<Boolean> completed : entry.completions.values()) {
            completed.accept(!entry.invalid);
        }
        entry.points.clear();
        entry.completions.clear();
    }

    synchronized void invalidate(Key key) {
        Entry entry = entries.get(key);
        if (entry != null) {
            retire(entry);
        }
    }

    synchronized void invalidateWorld(UUID world) {
        for (Entry entry : List.copyOf(occupied)) {
            if (entry.key.worldId().equals(world)) {
                retire(entry);
            }
        }
        gaps.remove(world);
        paused.remove(world);
        gapVersions.remove(world);
    }

    private void retire(Entry entry) {
        entry.invalid = true;
        entries.remove(entry.key, entry);
        foreground.remove(entry);
        background.remove(entry);
        if (!entry.submitted || (!entry.running && entry.task != null && entry.task.cancel())) {
            release(entry);
        }
    }

    void stop() {
        synchronized (this) {
            active = false;
            for (Entry entry : List.copyOf(occupied)) {
                retire(entry);
            }
            gaps.clear();
            paused.clear();
            gapVersions.clear();
        }
        lifecycle.writeLock().lock();
        try {
            // 已执行的短回调退出后才允许关闭审计。
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    synchronized void pauseWorld(UUID world) {
        markGap(world);
        paused.add(world);
    }

    synchronized void markGap(UUID world) {
        gaps.add(world);
        gapVersions.merge(world, 1L, Long::sum);
    }

    synchronized void covered(UUID world) {
        gaps.remove(world);
        paused.remove(world);
        gapVersions.remove(world);
    }

    synchronized long gapVersion(UUID world) {
        return gapVersions.getOrDefault(world, 0L);
    }

    synchronized void covered(UUID world, long version) {
        if (gapVersion(world) == version) {
            covered(world);
        }
    }

    boolean update(Runnable change) {
        lifecycle.writeLock().lock();
        try {
            synchronized (this) {
                if (!active) {
                    return false;
                }
                change.run();
                return true;
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    synchronized boolean automaticAllowed(UUID world) {
        return !paused.contains(world);
    }

    synchronized Stats stats() {
        return new Stats(occupied.size(), inFlight, highWater, rejected, Set.copyOf(gaps), Set.copyOf(paused));
    }

    synchronized boolean pending(Key key) {
        return entries.containsKey(key);
    }
}
