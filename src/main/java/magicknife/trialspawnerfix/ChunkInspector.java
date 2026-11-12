package magicknife.trialspawnerfix;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TrialSpawner;

final class ChunkInspector {
    private final TrialSpawnerFixPlugin plugin;
    private final RepairService repair;

    ChunkInspector(TrialSpawnerFixPlugin plugin, RepairService repair) {
        this.plugin = plugin;
        this.repair = repair;
    }

    CheckCoordinator.Cursor slice(CheckCoordinator.Ticket request, CheckCoordinator.Cursor cursor,
                                  BooleanSupplier valid) {
        var key = request.key();
        World world = Bukkit.getWorld(key.worldId());
        PluginSettings settings = plugin.settings();
        if (world == null || settings == null || !valid.getAsBoolean()) {
            if (request.point() != null) {
                request.point().completed().accept("检查已完成 result=UNAVAILABLE");
            }
            return null;
        }
        requireOwner(world, request);
        if (!world.isChunkLoaded(key.x(), key.z())) {
            if (request.point() != null) {
                request.point().completed().accept("检查已完成 result=UNAVAILABLE（区块未加载）");
            }
            return null;
        }
        if (request.point() != null) {
            single(world, request, valid);
            return null;
        }
        if (!settings.enables(key.worldId())) {
            return null;
        }
        long started = System.nanoTime();
        try {
            if (cursor != null && cursor.positions().size() > settings.candidateLimit()) {
                plugin.recordDenseChunk(request, cursor.positions().size());
                return null;
            }
            if (cursor == null) {
                cursor = discover(world, request, settings);
                if (cursor == null) {
                    return null;
                }
            }
            long candidateStarted = System.nanoTime();
            int offset = cursor.offset();
            int checked = 0;
            while (offset < cursor.positions().size() && checked < settings.budget().candidatesPerSlice()
                    && valid.getAsBoolean()) {
                var position = cursor.positions().get(offset++);
                var context = new AuditRecord.Context(world.getUID(), world.getName(), position.x(), position.y(),
                        position.z(), request.generation(), request.scanId(), request.sources().stream()
                        .map(source -> source.name().toLowerCase(Locale.ROOT)).sorted()
                        .collect(java.util.stream.Collectors.joining(",")), request.actor());
                plugin.recordResult(repair.check(new BlockTarget(world, request, position, valid), context), context);
                checked++;
                if (System.nanoTime() - candidateStarted >= settings.budget().softBudgetMillis() * 1_000_000L) {
                    break;
                }
            }
            return offset < cursor.positions().size() ? new CheckCoordinator.Cursor(cursor.positions(), offset) : null;
        } finally {
            plugin.recordSlice(System.nanoTime() - started);
        }
    }

    private CheckCoordinator.Cursor discover(World world, CheckCoordinator.Ticket request, PluginSettings settings) {
        // 过滤 API 仍遍历方块实体位置；跨 tick 仅保留目标坐标。
        var states = world.getChunkAt(request.key().x(), request.key().z()).getTileEntities(block ->
                block.getType() == Material.TRIAL_SPAWNER
                        && block.getBlockData() instanceof org.bukkit.block.data.type.TrialSpawner data
                        && data.getTrialSpawnerState() == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN, false);
        if (states.size() > settings.candidateLimit()) {
            plugin.recordDenseChunk(request, states.size());
            return null;
        }
        var positions = new ArrayList<CheckCoordinator.Position>(states.size());
        for (var state : states) {
            positions.add(new CheckCoordinator.Position(state.getX(), state.getY(), state.getZ()));
        }
        plugin.recordChunk();
        return new CheckCoordinator.Cursor(positions, 0);
    }

    private void single(World world, CheckCoordinator.Ticket request, BooleanSupplier valid) {
        var point = request.point();
        var position = point.position();
        var context = new AuditRecord.Context(world.getUID(), world.getName(), position.x(), position.y(), position.z(),
                request.generation(), request.scanId(), "admin", point.actor());
        var target = new BlockTarget(world, request, position, valid);
        var result = repair.check(target, context);
        plugin.recordResult(result, context);
        var snapshot = target.available() ? target.read(plugin.settings()) : null;
        String details = "";
        if (snapshot != null) {
            var input = snapshot.input();
            var decision = CooldownPolicy.evaluate(input);
            details = " stage=" + input.stage() + " ominous=" + input.ominous() + " G=" + input.gameTime()
                    + " E=" + input.cooldownEnd() + " L=" + input.cooldownLength()
                    + " R=" + decision.remaining() + " reason=" + decision.reason();
            plugin.recordOperation(context, input, decision, point.strategy() == CooldownPolicy.Strategy.REPORT_ONLY ? "inspect" : "repair");
        }
        point.completed().accept("检查已完成 result=" + result + details);
    }

    private static void requireOwner(World world, CheckCoordinator.Ticket request) {
        if (!Bukkit.isOwnedByCurrentRegion(world, request.key().x(), request.key().z())) {
            throw new IllegalStateException("区块检查不在所属区域线程：" + request);
        }
    }

    private final class BlockTarget implements RepairService.Target {
        private final World world;
        private final CheckCoordinator.Ticket request;
        private final CheckCoordinator.Position position;
        private final BooleanSupplier valid;

        private BlockTarget(World world, CheckCoordinator.Ticket request, CheckCoordinator.Position position,
                            BooleanSupplier valid) {
            this.world = world;
            this.request = request;
            this.position = position;
            this.valid = valid;
        }

        @Override
        public boolean available() {
            requireOwner(world, request);
            return valid.getAsBoolean() && world.isChunkLoaded(request.key().x(), request.key().z());
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
                    settings.margin(), request.point() != null ? request.point().strategy()
                    : plugin.coordinator().automaticAllowed(world.getUID()) ? settings.strategy()
                    : CooldownPolicy.Strategy.REPORT_ONLY, settings.generation());
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
