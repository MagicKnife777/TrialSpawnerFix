package magicknife.trialspawnerfix;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class TrialSpawnerFixPlugin extends JavaPlugin {

    private long offset = -1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        long lastTime = getConfig().getLong("last-shutdown-gametime", -1L);

        if (lastTime != -1L) {
            World overworld = Bukkit.getWorlds().get(0);
            long currentTime = overworld.getGameTime();

            if (currentTime < lastTime) {
                offset = lastTime - currentTime;
                getLogger().warning("检测到游戏时间回滚！偏移量: " + offset + " ticks");
            }
        }

        // 注册监听器
        getServer().getPluginManager().registerEvents(new SpawnerListener(this, offset), this);
        getLogger().info("TrialSpawnerFix 已启用！");
    }

    @Override
    public void onDisable() {
        World overworld = Bukkit.getWorlds().get(0);
        getConfig().set("last-shutdown-gametime", overworld.getGameTime());
        saveConfig();
        getLogger().info("已记录当前游戏时间，TrialSpawnerFix 已禁用。");
    }
}
