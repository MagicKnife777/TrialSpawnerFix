package magicknife.trialspawnerfix;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class TrialSpawnerFixPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 尝试保存默认配置（前提是你必须建了 config.yml，否则会报错）
        saveDefaultConfig();

        // 注册监听器
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);

        getLogger().info("TrialSpawnerFix 已启用！(暴力修复模式)");
    }

    @Override
    public void onDisable() {
        // 服务器关闭时，记录当前的世界时间（保留此功能备用）
        World overworld = Bukkit.getWorlds().get(0);
        getConfig().set("last-shutdown-gametime", overworld.getGameTime());
        saveConfig();
        getLogger().info("已记录当前游戏时间，TrialSpawnerFix 已禁用。");
    }
}
