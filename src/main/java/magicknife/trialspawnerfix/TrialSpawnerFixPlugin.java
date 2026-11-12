package magicknife.trialspawnerfix;

import java.io.File;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class TrialSpawnerFixPlugin extends JavaPlugin {
    private volatile PluginSettings settings;
    private AuditJournal audit;
    private SpawnerListener listener;
    private final LongAdder chunks = new LongAdder();
    private final LongAdder suspected = new LongAdder();
    private final LongAdder repaired = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder candidates = new LongAdder();
    private final LongAdder elapsed = new LongAdder();
    private enum Warning {
        SUSPECTED, AUDIT_UNAVAILABLE, DENSE_CHUNK
    }

    private final java.util.EnumMap<Warning, WarningWindow> warnings = new java.util.EnumMap<>(Warning.class);

    private static final class WarningWindow {
        private long next;
        private long suppressed;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.load(new File(getDataFolder(), "config.yml"));
            settings = PluginSettings.parse(config, 1);
            if (config.contains("last-shutdown-gametime")) {
                getLogger().info("event=config_legacy 字段 last-shutdown-gametime 已停用，可从配置中移除。");
            }
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "event=config_invalid 扫描与修改已禁用。", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (settings.auditEnabled()) {
            try {
                audit = new AuditJournal(settings.auditCapacity(), new AuditFiles(getDataFolder().toPath()),
                        getLogger(), getPluginMeta().getVersion(), Bukkit.getVersion());
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "event=audit_failed 审计初始化失败，仅保留只读检查。", exception);
            }
        }
        var repair = new RepairService(this::settings, audit, getLogger());
        listener = new SpawnerListener(this, new ChunkInspector(this, repair));
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("event=enabled strategy=" + settings.strategy().configValue()
                + " 自然加载检查已启用；启动扫描、预算分片和管理员接口尚未实现。");
    }

    @Override
    public void onDisable() {
        settings = null;
        if (listener != null) {
            listener.stop();
        }
        if (audit != null) {
            audit.close();
        }
        getLogger().info("event=summary chunks_checked=" + chunks.sum() + " candidates_checked=" + candidates.sum()
                + " suspected=" + suspected.sum() + " repaired=" + repaired.sum() + " failed=" + failed.sum()
                + " skipped=" + skipped.sum() + " elapsed_ns=" + elapsed.sum() + " warnings_suppressed="
                + warnings.values().stream().mapToLong(window -> window.suppressed).sum());
        getLogger().info("event=disabled TrialSpawnerFix 已禁用。");
    }

    PluginSettings settings() {
        return settings;
    }

    void recordChunk(long nanos) {
        chunks.increment();
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
                warn(Warning.SUSPECTED, "event=suspected 检测到疑似异常冷却：" + context);
            }
            case AUDIT_UNAVAILABLE -> {
                suspected.increment();
                skipped.increment();
                warn(Warning.AUDIT_UNAVAILABLE, "event=audit_unavailable 审计不可用，恢复已跳过：" + context);
            }
            case UPDATE_FAILED, READBACK_MISMATCH -> {
                suspected.increment();
                failed.increment();
            }
            case ERROR -> failed.increment();
            default -> skipped.increment();
        }
    }

    void recordDenseChunk(ChunkInspector.Request request, int count) {
        warn(Warning.DENSE_CHUNK, "event=dense_chunk candidates=" + count + " 批量恢复已跳过：" + request);
    }

    private synchronized void warn(Warning reason, String message) {
        WarningWindow window = warnings.computeIfAbsent(reason, ignored -> new WarningWindow());
        long now = System.nanoTime();
        if (now >= window.next) {
            getLogger().warning(message + " suppressed=" + window.suppressed);
            window.suppressed = 0;
            window.next = now + 60_000_000_000L;
        } else {
            window.suppressed++;
        }
    }
}
