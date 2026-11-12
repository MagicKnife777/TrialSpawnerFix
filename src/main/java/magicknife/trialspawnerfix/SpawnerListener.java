package magicknife.trialspawnerfix;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

final class SpawnerListener implements Listener {
    private final CheckCoordinator coordinator;

    SpawnerListener(CheckCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        coordinator.submit(new CheckCoordinator.Key(event.getWorld().getUID(), event.getChunk().getX(),
                event.getChunk().getZ()), CheckCoordinator.Source.CHUNK_LOAD, 0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        coordinator.invalidate(new CheckCoordinator.Key(event.getWorld().getUID(), event.getChunk().getX(),
                event.getChunk().getZ()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        coordinator.invalidateWorld(event.getWorld().getUID());
    }
}
