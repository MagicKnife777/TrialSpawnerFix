package magicknife.trialspawnerfix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;

final class LoadedScans {
    private static final class Scan {
        private final long id;
        private final ScanCursor cursor;
        private final CheckCoordinator.Source source;
        private final AtomicInteger outstanding = new AtomicInteger();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final List<Consumer<String>> listeners = new ArrayList<>();
        private boolean rescan;
        private final long started = System.nanoTime();
        private final long gapVersion;
        private String actor;
        private final TrialSpawnerFixPlugin.Totals before;

        private Scan(long id, ScanCursor cursor, CheckCoordinator.Source source, long gapVersion, TrialSpawnerFixPlugin.Totals before) {
            this.id = id;
            this.cursor = cursor;
            this.source = source;
            this.gapVersion = gapVersion;
            this.before = before;
        }
    }

    private final TrialSpawnerFixPlugin plugin;
    private final CheckCoordinator coordinator;
    private final Map<UUID, Scan> scans = new HashMap<>();
    private final Map<UUID, RollbackWatch> times = new HashMap<>();
    private final Map<UUID, Long> retryAt = new HashMap<>();
    private long ids;
    private int tick;
    private volatile String lastResult = "尚无完成扫描";

    LoadedScans(TrialSpawnerFixPlugin plugin) {
        this.plugin = plugin;
        coordinator = plugin.coordinator();
    }

    void startup() {
        if (plugin.settings().scanOnStartup()) {
            for (World world : Bukkit.getWorlds()) {
                requestBackground(world.getUID(), CheckCoordinator.Source.STARTUP);
            }
        }
    }

    long request(World world, CheckCoordinator.Source source, CommandRequest.Area area, String actor, Consumer<String> completed) {
        return request(world, source, area, actor, completed, 0);
    }

    private long request(World world, CheckCoordinator.Source source, CommandRequest.Area area, String actor,
                         Consumer<String> completed, long reuseId) {
        requireGlobal();
        if (!plugin.settings().enables(world.getUID())) {
            throw new IllegalArgumentException("该世界未启用检查。");
        }
        Scan existing = scans.get(world.getUID());
        if (existing != null) {
            if (area != null) {
                throw new IllegalArgumentException("该世界已有扫描，请等待完成后再提交范围扫描。");
            }
            existing.rescan = true;
            if (actor != null) {
                existing.actor = actor;
            }
            if (completed != null) {
                existing.listeners.add(completed);
            }
            return existing.id;
        }
        long version = coordinator.gapVersion(world.getUID());
        // 固定平台的 API 从并发 fullChunks 表枚举坐标；不读取区块状态。
        var loaded = world.getLoadedChunks();
        int limit = plugin.settings().budget().loadedScanChunks();
        if (loaded.length > limit) {
            coordinator.update(() -> coordinator.pauseWorld(world.getUID()));
            plugin.warn(world.getUID(), "coverage_incomplete", "已加载快照超过上限 count=" + loaded.length, null);
            retryAt.put(world.getUID(), System.nanoTime() + 30_000_000_000L);
            throw new IllegalArgumentException("已加载快照超过上限；该世界自动修改已暂停，单点命令仍可用。");
        }
        var keys = new ArrayList<CheckCoordinator.Key>();
        for (var chunk : loaded) {
            if (area == null || area.contains(chunk.getX(), chunk.getZ())) {
                keys.add(new CheckCoordinator.Key(world.getUID(), chunk.getX(), chunk.getZ()));
            }
        }
        var scan = new Scan(reuseId == 0 ? ++ids : reuseId, new ScanCursor(keys), source, area == null ? version : -1, plugin.totals());
        scan.actor = actor;
        if (completed != null) {
            scan.listeners.add(completed);
        }
        scans.put(world.getUID(), scan);
        plugin.getLogger().info("event=scan_queued scan_id=" + scan.id + " world=" + world.getUID() + " chunks=" + keys.size());
        return scan.id;
    }

    void tick() {
        requireGlobal();
        long now = System.nanoTime();
        for (var entry : List.copyOf(scans.entrySet())) {
            Scan scan = entry.getValue();
            int before = scan.cursor.remaining();
            scan.cursor.feed(coordinator, scan.source, scan.id, plugin.settings().budget().dispatchPerTick(), scan.actor, success -> {
                if (!success) {
                    scan.failed.set(true);
                }
                scan.outstanding.decrementAndGet();
            });
            scan.outstanding.addAndGet(before - scan.cursor.remaining());
            if (scan.cursor.remaining() == 0 && scan.outstanding.get() == 0) {
                finish(entry.getKey(), scan);
            }
        }
        if (++tick % 100 == 0) {
            observeTimes();
        }
        for (var entry : times.entrySet()) {
            if (plugin.settings().observeTime() && entry.getValue().due(now)) {
                requestBackground(entry.getKey(), CheckCoordinator.Source.TIME_ROLLBACK);
            }
        }
        for (UUID world : coordinator.stats().gaps()) {
            if (!scans.containsKey(world) && now >= retryAt.getOrDefault(world, Long.MIN_VALUE)) {
                retryAt.put(world, now + 30_000_000_000L);
                requestBackground(world, CheckCoordinator.Source.RECOVERY);
            }
        }
    }

    private void finish(UUID world, Scan scan) {
        scans.remove(world);
        boolean paused = !coordinator.automaticAllowed(world);
        if (scan.failed.get()) {
            coordinator.markGap(world);
        }
        if (!scan.failed.get() && scan.gapVersion >= 0) {
            coordinator.covered(world, scan.gapVersion);
        }
        World loadedWorld = Bukkit.getWorld(world);
        if ((scan.rescan || (paused && coordinator.automaticAllowed(world))) && loadedWorld != null
                && plugin.settings().enables(world)) {
            try {
                request(loadedWorld, CheckCoordinator.Source.RECOVERY, null, scan.actor, null, scan.id);
                scans.get(world).listeners.addAll(scan.listeners);
                return;
            } catch (IllegalArgumentException exception) {
                scan.failed.set(true);
                plugin.warn(world, "scan_rejected", exception.getMessage(), null);
            }
        }
        String result = "扫描已完成 scan_id=" + scan.id + " failed=" + scan.failed.get()
                + " coverage_incomplete=" + coordinator.stats().gaps().contains(world)
                + " elapsed_ns=" + (System.nanoTime() - scan.started)
                + " window_stats=" + plugin.totals().minus(scan.before).format();
        plugin.getLogger().info("event=scan_completed " + result + " " + plugin.summary());
        lastResult = result;
        scan.listeners.forEach(listener -> listener.accept(result));
    }

    private void observeTimes() {
        var worlds = Bukkit.getWorlds();
        var live = worlds.stream().map(World::getUID).collect(java.util.stream.Collectors.toSet());
        times.keySet().retainAll(live);
        retryAt.keySet().retainAll(live);
        for (World world : worlds) {
            if (plugin.settings().observeTime() && plugin.settings().enables(world.getUID())
                    && times.computeIfAbsent(world.getUID(), ignored -> new RollbackWatch()).observe(world.getGameTime())) {
                plugin.warn(world.getUID(), "time_rollback", "检测到游戏时间下降，将合并触发已加载扫描。", null);
            }
        }
    }

    private void requestBackground(UUID worldId, CheckCoordinator.Source source) {
        World world = Bukkit.getWorld(worldId);
        if (world != null && plugin.settings().enables(worldId)) {
            try {
                request(world, source, null, null, null);
            } catch (IllegalArgumentException exception) {
                plugin.warn(worldId, "scan_rejected", exception.getMessage(), null);
            }
        }
    }

    void stop() {
        scans.values().forEach(scan -> scan.listeners.forEach(listener -> listener.accept("扫描已取消：插件停用。")));
        scans.clear();
        times.clear();
        retryAt.clear();
    }

    String lastResult() {
        return lastResult;
    }

    private static void requireGlobal() {
        if (!Bukkit.isGlobalTickThread()) {
            throw new IllegalStateException("元数据枚举必须在全局调度线程执行");
        }
    }
}
