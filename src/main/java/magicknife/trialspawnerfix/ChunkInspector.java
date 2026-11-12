package magicknife.trialspawnerfix;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TrialSpawner;

final class ChunkInspector {
    record Request(UUID worldId, int chunkX, int chunkZ, long generation) {
    }

    record Position(int x, int y, int z) {
    }

    private final TrialSpawnerFixPlugin plugin;
    private final RepairService repair;

    ChunkInspector(TrialSpawnerFixPlugin plugin, RepairService repair) {
        this.plugin = plugin;
        this.repair = repair;
    }

    void inspect(Request request, BooleanSupplier valid) {
        World world = Bukkit.getWorld(request.worldId());
        PluginSettings settings = plugin.settings();
        if (world == null || settings == null || !settings.enables(request.worldId()) || !valid.getAsBoolean()) {
            return;
        }
        requireOwner(world, request);
        if (!world.isChunkLoaded(request.chunkX(), request.chunkZ())) {
            return;
        }
        long started = System.nanoTime();
        // 过滤 API 仍遍历方块实体位置，但不会为非目标实体创建快照。
        var states = world.getChunkAt(request.chunkX(), request.chunkZ()).getTileEntities(block ->
                block.getType() == Material.TRIAL_SPAWNER
                        && block.getBlockData() instanceof org.bukkit.block.data.type.TrialSpawner data
                        && data.getTrialSpawnerState() == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN, false);
        if (states.size() > settings.candidateLimit()) {
            plugin.recordDenseChunk(request, states.size());
            return;
        }
        var positions = new ArrayList<Position>(states.size());
        for (var state : states) {
            positions.add(new Position(state.getX(), state.getY(), state.getZ()));
        }
        states = null;
        for (Position position : positions) {
            var context = new AuditRecord.Context(world.getUID(), world.getName(), position.x(), position.y(),
                    position.z(), request.generation(), 0, "chunk-load", null);
            plugin.recordResult(repair.check(new BlockTarget(world, request, position, valid), context), context);
        }
        plugin.recordChunk(System.nanoTime() - started);
    }

    private static void requireOwner(World world, Request request) {
        if (!Bukkit.isOwnedByCurrentRegion(world, request.chunkX(), request.chunkZ())) {
            throw new IllegalStateException("区块检查不在所属区域线程：" + request);
        }
    }

    private static final class BlockTarget implements RepairService.Target {
        private final World world;
        private final Request request;
        private final Position position;
        private final BooleanSupplier valid;

        private BlockTarget(World world, Request request, Position position, BooleanSupplier valid) {
            this.world = world;
            this.request = request;
            this.position = position;
            this.valid = valid;
        }

        @Override
        public boolean available() {
            requireOwner(world, request);
            return valid.getAsBoolean() && world.isChunkLoaded(request.chunkX(), request.chunkZ());
        }

        private Block block() {
            return world.getBlockAt(position.x(), position.y(), position.z());
        }

        @Override
        public RepairService.Snapshot read(PluginSettings settings) {
            if (!(block().getState() instanceof TrialSpawner spawner)
                    || !(spawner.getBlockData() instanceof org.bukkit.block.data.type.TrialSpawner data)) {
                return null;
            }
            var input = new CooldownPolicy.Input(settings.enables(world.getUID()),
                    CooldownPolicy.Stage.valueOf(data.getTrialSpawnerState().name()), spawner.isOminous(),
                    world.getGameTime(), spawner.getCooldownEnd(), spawner.getCooldownLength(),
                    settings.margin(), settings.strategy(), settings.generation());
            return new RepairService.Snapshot() {
                @Override
                public CooldownPolicy.Input input() {
                    return input;
                }

                @Override
                public boolean writeEnd(long end) {
                    spawner.setCooldownEnd(end);
                    return spawner.update(false, false);
                }
            };
        }

        @Override
        public Long readEnd() {
            if (!available()) {
                return null;
            }
            return block().getState() instanceof TrialSpawner spawner ? spawner.getCooldownEnd() : null;
        }
    }
}
