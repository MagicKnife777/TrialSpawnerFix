package magicknife.trialspawnerfix;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

final class SpawnerListener implements Listener {
    private record Key(UUID worldId, int x, int z) {
    }

    private final TrialSpawnerFixPlugin plugin;
    private final ChunkInspector inspector;
    private final ConcurrentHashMap<Key, Long> pending = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean active = true;

    SpawnerListener(TrialSpawnerFixPlugin plugin, ChunkInspector inspector) {
        this.plugin = plugin;
        this.inspector = inspector;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!active) {
            return;
        }
        Key key = new Key(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        long generation = generations.incrementAndGet();
        if (pending.putIfAbsent(key, generation) != null) {
            return;
        }
        var request = new ChunkInspector.Request(key.worldId(), key.x(), key.z(), generation);
        try {
            Bukkit.getRegionScheduler().run(plugin, event.getWorld(), key.x(), key.z(), task -> run(key, request));
        } catch (RuntimeException exception) {
            pending.remove(key, generation);
            plugin.getLogger().log(Level.SEVERE, "event=schedule_failed " + request, exception);
        }
    }

    private void run(Key key, ChunkInspector.Request request) {
        lifecycle.readLock().lock();
        try {
            inspector.inspect(request, () -> active && Long.valueOf(request.generation()).equals(pending.get(key)));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "event=chunk_failed " + request, exception);
        } finally {
            pending.remove(key, request.generation());
            lifecycle.readLock().unlock();
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        pending.remove(new Key(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        pending.keySet().removeIf(key -> key.worldId().equals(world));
    }

    void stop() {
        active = false;
        lifecycle.writeLock().lock();
        try {
            pending.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
