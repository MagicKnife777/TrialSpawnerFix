package magicknife.trialspawnerfix;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class TrialSpawnerFixPlugin extends JavaPlugin {
    private volatile PluginSettings settings;

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
        getLogger().info("event=enabled strategy=" + settings.strategy().configValue()
                + " 阶段 1 尚未接入区块检查，修改功能未启用。");
    }

    @Override
    public void onDisable() {
        settings = null;
        getLogger().info("event=disabled TrialSpawnerFix 已禁用。");
    }

    PluginSettings settings() {
        return settings;
    }
}
