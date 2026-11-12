package magicknife.trialspawnerfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.TrialSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class SpawnerListener implements Listener {

    private final TrialSpawnerFixPlugin plugin;

    public SpawnerListener(TrialSpawnerFixPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // 获取当前世界的总游戏时间
        long currentTime = event.getWorld().getGameTime();

        // 遍历加载的区块里的所有方块实体
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (state instanceof TrialSpawner spawner) {
                long cooldownEnd = spawner.getCooldownEnd();

                // 如果冷却结束时间大于0，说明在冷却中
                if (cooldownEnd > 0) {
                    // 计算剩余冷却时间
                    long remaining = cooldownEnd - currentTime;

                    // 正常的最大冷却只有 30 分钟（36000刻）
                    // 如果剩余时间超过 36000，绝对是异常数据！
                    if (remaining > 36000L) {
                        plugin.getLogger().warning("检测到异常的巨额冷却！原剩余: " + remaining + " ticks，正在强行修复...");

                        Location loc = spawner.getLocation();

                        // 在 Folia/Paper 架构下，修改方块必须使用 RegionScheduler 调度到对应的区域线程
                        Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
                            // 再次获取最新的状态，防止被其他线程修改
                            if (loc.getBlock().getState() instanceof TrialSpawner currentSpawner) {
                                // 核心修复：直接把冷却结束时间设为 0（立刻解除冷却）
                                currentSpawner.setCooldownEnd(0);
                                currentSpawner.update(true); // 保存修改
                                plugin.getLogger().info("已成功修复一个试炼刷怪笼的异常冷却。");
                            }
                        });
                    }
                }
            }
        }
    }
}
