package magicknife.trialspawnerfix;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class TrialSpawnerFixPlugin extends JavaPlugin {
    private volatile RepairService.Configuration configuration;
    private CheckCoordinator coordinator;
    private ChunkInspector inspector;
    private LoadedScans scans;
    private ScheduledTask dispatcher;
    private final AtomicBoolean reloading = new AtomicBoolean();
    private final AtomicReference<AuditJournal> pendingAudit = new AtomicReference<>();
    private final AuditJournal.Identity auditIdentity = new AuditJournal.Identity();
    private final LongAdder chunks = new LongAdder();
    private final LongAdder suspected = new LongAdder();
    private final LongAdder repaired = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder candidates = new LongAdder();
    private final LongAdder elapsed = new LongAdder();
    private DiagnosticLog diagnostics;
    private long summaryAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings initial;
        try {
            YamlConfiguration config = readConfig();
            initial = PluginSettings.parse(config, 1);
            if (config.contains("last-shutdown-gametime")) {
                getLogger().info("event=config_legacy 字段 last-shutdown-gametime 已停用，可从配置中移除。");
            }
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "event=config_invalid 扫描与修改已禁用。", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        AuditJournal audit = null;
        try {
            audit = openAudit(initial);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "event=audit_failed 审计初始化失败，仅保留只读检查。", exception);
        }
        configuration = new RepairService.Configuration(initial, audit);
        diagnostics = new DiagnosticLog(getLogger(), System::nanoTime,
                () -> configuration == null ? null : configuration.audit());
        inspector = new ChunkInspector(this, new RepairService(() -> configuration, diagnostics));
        coordinator = new CheckCoordinator(() -> settings().budget(), this::schedule, inspector::slice, diagnostics);
        scans = new LoadedScans(this);
        getServer().getPluginManager().registerEvents(new SpawnerListener(this, coordinator), this);
        getCommand("trialspawnerfix").setExecutor(new SpawnerCommand(this));
        dispatcher = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, ignored -> {
            scans.tick();
            coordinator.tick();
            long now = System.nanoTime();
            if (now >= summaryAt) {
                summaryAt = now + 60_000_000_000L;
                getLogger().info("event=summary " + summary());
            }
        }, 1, 1);
        Bukkit.getGlobalRegionScheduler().run(this, ignored -> scans.startup());
        getLogger().info("event=enabled strategy=" + initial.strategy().configValue() + " 各检查入口和管理员命令已启用。");
    }

    private CheckCoordinator.Cancellation schedule(CheckCoordinator.Key key, Runnable callback) {
        var world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            coordinator.invalidate(key);
            return () -> true;
        }
        var task = Bukkit.getRegionScheduler().run(this, world, key.x(), key.z(), ignored -> callback.run());
        return () -> switch (task.cancel()) {
            case CANCELLED_BY_CALLER, CANCELLED_ALREADY -> true;
            default -> false;
        };
    }

    private YamlConfiguration readConfig() throws Exception {
        var config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        return config;
    }

    private AuditJournal openAudit(PluginSettings settings) throws Exception {
        if (!settings.auditEnabled()) {
            return null;
        }
        var journal = new AuditJournal(settings.auditCapacity(), new AuditFiles(getDataFolder().toPath()),
                getLogger(), getPluginMeta().getVersion(), Bukkit.getVersion(), auditIdentity);
        try {
            journal.verifyReady();
            return journal;
        } catch (Exception exception) {
            journal.close();
            throw exception;
        }
    }

    void reloadSettings(Consumer<String> completed) {
        if (!reloading.compareAndSet(false, true)) {
            completed.accept("已有配置重载正在进行。");
            return;
        }
        RepairService.Configuration previous = configuration;
        Bukkit.getAsyncScheduler().runNow(this, ignored -> {
            try {
                var next = PluginSettings.parse(readConfig(), previous.settings().generation() + 1);
                AuditJournal audit = openAudit(next);
                pendingAudit.set(audit);
                if (configuration == null) {
                    closePendingAudit();
                    return;
                }
                Bukkit.getGlobalRegionScheduler().run(this, task -> {
                    applyReload(previous, next, audit, completed);
                });
            } catch (Exception exception) {
                closePendingAudit();
                reloading.set(false);
                getLogger().log(Level.SEVERE, "event=config_reload_failed 保持原有效配置。", exception);
                completed.accept("重载失败，保持原配置：" + exception.getMessage());
            }
        });
    }

    private void applyReload(RepairService.Configuration previous, PluginSettings next, AuditJournal audit,
                             Consumer<String> completed) {
        try {
            boolean applied = coordinator.update(() -> {
                var stats = coordinator.stats();
                if (stats.pending() > next.budget().pendingChunks() || stats.inFlight() > next.budget().inFlightChunks()) {
                    throw new IllegalArgumentException("新容量小于当前积压，请等待队列下降后重载。");
                }
                configuration = new RepairService.Configuration(next, audit);
            });
            if (!applied) {
                closePendingAudit();
                completed.accept("配置重载已取消：插件停用。");
                return;
            }
            pendingAudit.compareAndSet(audit, null);
            getLogger().info("event=config_reloaded generation=" + next.generation() + " strategy=" + next.strategy().configValue());
            completed.accept("配置重载已完成 generation=" + next.generation());
            if (previous.audit() != null) {
                Bukkit.getAsyncScheduler().runNow(this, close -> previous.audit().close());
            }
        } catch (IllegalArgumentException exception) {
            closePendingAudit();
            completed.accept("重载失败，保持原配置：" + exception.getMessage());
        } finally {
            reloading.set(false);
        }
    }

    private void closePendingAudit() {
        AuditJournal audit = pendingAudit.getAndSet(null);
        if (audit != null) {
            audit.close();
        }
    }

    @Override
    public void onDisable() {
        if (dispatcher != null) {
            dispatcher.cancel();
        }
        if (scans != null) {
            scans.stop();
        }
        if (coordinator != null) {
            coordinator.stop();
        }
        RepairService.Configuration previous = configuration;
        configuration = null;
        closePendingAudit();
        if (previous != null && previous.audit() != null) {
            previous.audit().close();
        }
        getLogger().info("event=summary " + summary());
        getLogger().info("event=disabled TrialSpawnerFix 已禁用。");
    }

    PluginSettings settings() {
        return configuration == null ? null : configuration.settings();
    }
    CheckCoordinator coordinator() {
        return coordinator;
    }
    LoadedScans scans() {
        return scans;
    }

    String status() {
        var current = configuration;
        return "strategy=" + current.settings().strategy().configValue() + " worlds="
                + (current.settings().worlds().isEmpty() ? "all" : current.settings().worlds())
                + " generation=" + current.settings().generation() + " audit_healthy="
                + (current.audit() != null && current.audit().healthy()) + " latest_scan=" + scans.lastResult() + " " + summary();
    }

    String summary() {
        var stats = coordinator == null ? null : coordinator.stats();
        var current = configuration;
        return totals().format()
                + " audit_dropped=" + (current == null || current.audit() == null ? 0 : current.audit().dropped())
                + " warnings_suppressed=" + (diagnostics == null ? 0 : diagnostics.suppressed())
                + " queue=" + stats;
    }

    record Totals(long chunks, long candidates, long suspected, long repaired, long failed, long skipped, long nanos) {
        Totals minus(Totals previous) {
            return new Totals(chunks - previous.chunks, candidates - previous.candidates, suspected - previous.suspected,
                    repaired - previous.repaired, failed - previous.failed, skipped - previous.skipped, nanos - previous.nanos);
        }
        String format() {
            return "chunks_checked=" + chunks + " candidates_checked=" + candidates + " suspected=" + suspected
                    + " repaired=" + repaired + " failed=" + failed + " skipped=" + skipped + " elapsed_ns=" + nanos;
        }
    }

    Totals totals() {
        return new Totals(chunks.sum(), candidates.sum(), suspected.sum(), repaired.sum(), failed.sum(), skipped.sum(), elapsed.sum());
    }

    void recordChunk() {
        chunks.increment();
    }
    void recordSlice(long nanos) {
        elapsed.add(nanos);
    }
    void recordResult(RepairService.Result result, AuditRecord.Context context) {
        candidates.increment();
        switch (result) {
            case REPAIRED -> {
                suspected.increment();
                repaired.increment();
            }
            case REPORTED -> {
                suspected.increment();
                warn(context.worldId(), "suspected", "检测到疑似异常冷却：" + context, null);
            }
            case AUDIT_UNAVAILABLE -> {
                suspected.increment();
                skipped.increment();
                warn(context.worldId(), "audit_unavailable", "审计不可用，恢复已跳过：" + context, null);
            }
            case UPDATE_FAILED, READBACK_MISMATCH, ERROR -> failed.increment();
            default -> skipped.increment();
        }
    }

    void recordDenseChunk(CheckCoordinator.Ticket request, int count) {
        coordinator.markGap(request.key().worldId());
        warn(request.key().worldId(), "dense_chunk", "candidates=" + count + " 批量恢复已跳过：" + request, null);
    }

    void recordOperation(AuditRecord.Context context, CooldownPolicy.Input input, CooldownPolicy.Decision decision, String event) {
        var current = configuration;
        if (current != null && current.audit() != null) {
            current.audit().reportEvent(context, input, decision, event, decision.reason().name(), "completed");
        }
    }

    void warn(UUID world, String reason, String message, Throwable exception) {
        diagnostics.report(world, reason, message, exception);
    }

    void unloadWorld(UUID world) {
        diagnostics.removeWorld(world);
    }
}
