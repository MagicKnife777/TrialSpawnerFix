package magicknife.trialspawnerfix.integration;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TrialSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegrationDriver extends JavaPlugin implements Listener {
    private final List<String> passed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private String strategy;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        World world = Bukkit.getWorlds().getFirst();
        var spawn = world.getSpawnLocation();
        int chunkX = spawn.getBlockX() >> 4;
        int chunkZ = spawn.getBlockZ() >> 4;
        Bukkit.getRegionScheduler().run(this, world, chunkX, chunkZ,
                task -> verify(world, chunkX, chunkZ));
    }

    private void verify(World world, int chunkX, int chunkZ) {
        try {
            assertThat(Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ), "区域所有权");
            var production = Bukkit.getPluginManager().getPlugin("TrialSpawnerFix");
            if (production == null || !production.isEnabled()) {
                throw new IllegalStateException("生产插件未启用");
            }
            strategy = ((JavaPlugin) production).getConfig().getString("strategy", "report-only");
            Object listener = field(production, "listener");
            Object inspector = field(listener, "inspector");
            Class<?> requestType = Class.forName("magicknife.trialspawnerfix.ChunkInspector$Request", true,
                    production.getClass().getClassLoader());
            Constructor<?> constructor = requestType.getDeclaredConstructor(UUID.class, int.class, int.class, long.class);
            constructor.setAccessible(true);
            Object request = constructor.newInstance(world.getUID(), chunkX, chunkZ, 1000L);
            Method inspect = inspector.getClass().getDeclaredMethod("inspect", requestType, BooleanSupplier.class);
            inspect.setAccessible(true);

            int x = chunkX << 4;
            int z = chunkZ << 4;
            long g = world.getGameTime();
            var fixtures = new ArrayList<Fixture>();
            for (boolean ominous : new boolean[]{false, true}) {
                for (var state : org.bukkit.block.data.type.TrialSpawner.State.values()) {
                    boolean cooldown = state == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN;
                    long end = g + (cooldown ? 72_201 : 999_999);
                    fixtures.add(place(world, x + fixtures.size(), z, state, ominous, 72_000, end));
                }
                fixtures.add(place(world, x + fixtures.size(), z,
                        org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN, ominous, 72_000, g + 72_200));
            }
            inspect.invoke(inspector, request, (BooleanSupplier) () -> true);
            for (Fixture fixture : fixtures) {
                TrialSpawner actual = (TrialSpawner) fixture.block().getState();
                var data = (org.bukkit.block.data.type.TrialSpawner) actual.getBlockData();
                boolean anomalous = fixture.stage() == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN
                        && fixture.end() - g > 72_200;
                long expected = !anomalous || strategy.equals("report-only") ? fixture.end()
                        : strategy.equals("expire-now") ? g : g + fixture.length();
                assertThat(actual.getCooldownEnd() == expected, "截止时间 " + fixture.stage() + " ominous=" + fixture.ominous());
                assertThat(data.getTrialSpawnerState() == fixture.stage() && actual.isOminous() == fixture.ominous()
                        && actual.getCooldownLength() == fixture.length() && actual.getNextSpawnAttempt() == 12345,
                        "保留阶段／不祥／长度／下一刷怪时间 " + fixture.stage() + " ominous=" + fixture.ominous());
            }
            Block stale = world.getBlockAt(x, 80, z + 2);
            placeAt(stale, org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN, false, 72_000, g + 999_999);
            inspect.invoke(inspector, request, (BooleanSupplier) () -> false);
            assertThat(((TrialSpawner) stale.getState()).getCooldownEnd() == g + 999_999, "无效加载代次跳过");
            // 通过真实监听入口验证后续区域调度，不让测试替代生产回调。
            Block scheduled = world.getBlockAt(x, 80, z + 1);
            placeAt(scheduled, org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN, true, 72_000, g + 999_999);
            Bukkit.getPluginManager().callEvent(new ChunkLoadEvent(world.getChunkAt(chunkX, chunkZ), false));
            Bukkit.getRegionScheduler().runDelayed(this, world, chunkX, chunkZ, task -> {
                try {
                    TrialSpawner actual = (TrialSpawner) scheduled.getState();
                    long end = actual.getCooldownEnd();
                    long now = world.getGameTime();
                    boolean matches = strategy.equals("report-only") ? end == g + 999_999
                            : strategy.equals("restart-cooldown") ? end >= g + 72_000 && end <= now + 72_000
                            : end >= g && end <= now;
                    assertThat(matches, "自然加载入口的区域调度与策略");
                } catch (Throwable exception) {
                    failed.add(exception.toString());
                    getLogger().log(java.util.logging.Level.SEVERE, "集成断言失败", exception);
                }
                finish();
            }, 4);
        } catch (Throwable exception) {
            failed.add(exception.toString());
            getLogger().log(java.util.logging.Level.SEVERE, "集成断言失败", exception);
            finish();
        }
    }

    private record Fixture(Block block, org.bukkit.block.data.type.TrialSpawner.State stage,
                           boolean ominous, int length, long end) {
    }

    private Fixture place(World world, int x, int z, org.bukkit.block.data.type.TrialSpawner.State stage,
                          boolean ominous, int length, long end) {
        Block block = world.getBlockAt(x, 80, z);
        placeAt(block, stage, ominous, length, end);
        return new Fixture(block, stage, ominous, length, end);
    }

    private void placeAt(Block block, org.bukkit.block.data.type.TrialSpawner.State stage,
                         boolean ominous, int length, long end) {
        block.setType(Material.TRIAL_SPAWNER, false);
        var data = (org.bukkit.block.data.type.TrialSpawner) block.getBlockData();
        data.setTrialSpawnerState(stage);
        data.setOminous(ominous);
        block.setBlockData(data, false);
        var spawner = (TrialSpawner) block.getState();
        spawner.setCooldownLength(length);
        spawner.setCooldownEnd(end);
        spawner.setNextSpawnAttempt(12345);
        if (!spawner.update(false, false)) {
            throw new IllegalStateException("无法写入夹具");
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void assertThat(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
        passed.add(name);
    }

    private void finish() {
        var result = new Result(strategy, List.copyOf(passed), List.copyOf(failed));
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                Files.writeString(Path.of("integration-result.json"), new Gson().toJson(result));
            } catch (Exception exception) {
                getLogger().log(java.util.logging.Level.SEVERE, "结构化结果写入失败", exception);
            } finally {
                Bukkit.getGlobalRegionScheduler().run(this, ignored -> Bukkit.shutdown());
            }
        });
    }

    private record Result(String strategy, List<String> passed, List<String> failed) {
    }
}
