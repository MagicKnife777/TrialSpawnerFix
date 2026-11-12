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
    private final long offset;

    public SpawnerListener(TrialSpawnerFixPlugin plugin, long offset) {
        this.plugin = plugin;
        this.offset = offset;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (offset <= 0) return;

        // 遍历区块内的方块实体
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (state instanceof TrialSpawner spawner) {
                long cooldownEnd = spawner.getCooldownEnd();

                if (cooldownEnd > 0) {
                    long correctedEnd = Math.max(0, cooldownEnd - offset);
                    Location location = spawner.getLocation();

                    // 使用 Folia 区域调度器在对应方块线程上安全更新
                    Bukkit.getRegionScheduler().execute(plugin, location, () -> {
                        BlockState currentState = location.getBlock().getState();
                        if (currentState instanceof TrialSpawner currentSpawner) {
                            currentSpawner.setCooldownEnd(correctedEnd);
                            currentSpawner.update(true);
                        }
                    });
                }
            }
        }
    }
}
