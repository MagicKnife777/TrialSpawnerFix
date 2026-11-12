package magicknife.trialspawnerfix.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.TrialSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

final class AdminChecks {
    private final JavaPlugin driver;
    private final JavaPlugin production;
    private final World world;
    private final int chunkX;
    private final int chunkZ;
    private final List<String> passed;
    private final List<String> failed;
    private final Runnable finished;
    private String originalConfig;
    private long initialGeneration;
    private long oldEnd;

    AdminChecks(JavaPlugin driver, JavaPlugin production, World world, int chunkX, int chunkZ,
                List<String> passed, List<String> failed, Runnable finished) {
        this.driver = driver;
        this.production = production;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.passed = passed;
        this.failed = failed;
        this.finished = finished;
    }

    void run() {
        var block = world.getBlockAt(chunkX << 4, 80, (chunkZ << 4) + 4);
        block.setType(Material.TRIAL_SPAWNER, false);
        var data = (org.bukkit.block.data.type.TrialSpawner) block.getBlockData();
        data.setTrialSpawnerState(org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN);
        block.setBlockData(data, false);
        var spawner = (TrialSpawner) block.getState();
        oldEnd = world.getGameTime() + 999_999;
        spawner.setCooldownLength(72_000);
        spawner.setCooldownEnd(oldEnd);
        check(spawner.update(false, false), "管理员夹具写入");
        global(this::inspect);
    }

    private String pointArguments() {
        return world.getUID() + " " + (chunkX << 4) + " 80 " + ((chunkZ << 4) + 4);
    }

    private TrialSpawner spawner() {
        return (TrialSpawner) world.getBlockAt(chunkX << 4, 80, (chunkZ << 4) + 4).getState();
    }

    private void inspect() throws Exception {
        check(Bukkit.isGlobalTickThread(), "已加载元数据在全局线程枚举");
        var denied = new ArrayList<String>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission", "isOp", "isPermissionSet" -> false;
                    case "getName" -> "phase4-denied";
                    case "toString" -> "phase4-denied";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getServer" -> Bukkit.getServer();
                    case "sendMessage" -> { denied.add(String.valueOf(args[0])); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        production.getCommand("trialspawnerfix").execute(sender, "trialspawnerfix",
                ("repair " + pointArguments() + " expire-now").split(" "));
        check(denied.stream().anyMatch(message -> message.contains("没有权限")), "权限不足明确拒绝");
        dispatch("inspect " + pointArguments(), () -> {
            check(spawner().getCooldownEnd() == oldEnd, "inspect 在修改策略下仍只读");
            global(this::repair);
        });
    }

    private void repair() throws Exception {
        dispatch("repair " + pointArguments() + " expire-now", () -> {
            check(spawner().getCooldownEnd() <= world.getGameTime(), "显式恢复共用写入读回保护");
            global(this::unloaded);
        });
    }

    private void unloaded() throws Exception {
        dispatch("inspect " + world.getUID() + " 29984000 80 29984000", () -> global(this::scan));
    }

    private void scan() throws Exception {
        check(java.util.Arrays.stream(world.getLoadedChunks()).noneMatch(chunk -> chunk.getX() == 1_874_000
                && chunk.getZ() == 1_874_000), "未加载单点检查不加载区块");
        dispatch("scan area " + world.getUID() + " " + chunkX + " " + chunkZ + " 0", () -> {
            check(true, "范围扫描完成异步返回");
            async(this::invalidReload);
        });
    }

    private Path configPath() {
        return production.getDataFolder().toPath().resolve("config.yml");
    }

    private void invalidReload() throws Exception {
        originalConfig = Files.readString(configPath());
        initialGeneration = generation();
        Files.writeString(configPath(), "strategy: invalid\n");
        global(() -> reload(message -> {
            try {
                check(message.contains("失败") && generation() == initialGeneration, "非法重载保留旧配置及代次");
                async(this::validReload);
            } catch (Exception exception) { fail(exception); }
        }));
    }

    private void validReload() throws Exception {
        Files.writeString(configPath(), "strategy: report-only\n");
        global(() -> reload(message -> {
            try {
                check(message.contains("已完成") && generation() == initialGeneration + 1, "有效重载原子替换并递增代次");
                Method status = production.getClass().getDeclaredMethod("status");
                status.setAccessible(true);
                check(status.invoke(production).toString().contains("audit_healthy=true"), "重载审计真实写入验证成功");
                async(() -> {
                    Files.writeString(configPath(), originalConfig);
                    global(() -> reload(done -> {
                        if (done.contains("已完成")) {
                            check(true, "原配置恢复完成");
                            finished.run();
                        } else {
                            fail(new IllegalStateException(done));
                        }
                    }));
                });
            } catch (Exception exception) { fail(exception); }
        }));
    }

    private long generation() throws Exception {
        Method settings = production.getClass().getDeclaredMethod("settings");
        settings.setAccessible(true);
        Object value = settings.invoke(production);
        return (long) value.getClass().getMethod("generation").invoke(value);
    }

    private void reload(Consumer<String> result) throws Exception {
        Method reload = production.getClass().getDeclaredMethod("reloadSettings", Consumer.class);
        reload.setAccessible(true);
        reload.invoke(production, result);
    }

    private void dispatch(String arguments, Runnable next) throws Exception {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "trialspawnerfix " + arguments);
        Object executor = production.getCommand("trialspawnerfix").getExecutor();
        Field field = executor.getClass().getDeclaredField("pending");
        field.setAccessible(true);
        int[] ticks = {0};
        Bukkit.getRegionScheduler().runAtFixedRate(driver, world, chunkX, chunkZ, task -> {
            try {
                Set<?> pending = (Set<?>) field.get(executor);
                if (pending.contains("console")) {
                    if (++ticks[0] > 1200) {
                        throw new IllegalStateException("管理员命令未完成：" + arguments);
                    }
                    return;
                }
                task.cancel();
                next.run();
            } catch (Exception exception) {
                task.cancel();
                fail(exception);
            }
        }, 1, 1);
    }

    private interface Step { void run() throws Exception; }

    private void global(Step step) {
        Bukkit.getGlobalRegionScheduler().run(driver, ignored -> {
            try { step.run(); } catch (Exception exception) { fail(exception); }
        });
    }
    private void async(Step step) {
        Bukkit.getAsyncScheduler().runNow(driver, ignored -> {
            try { step.run(); } catch (Exception exception) { fail(exception); }
        });
    }
    private void check(boolean condition, String name) {
        if (!condition) {
            throw new IllegalStateException(name);
        }
        passed.add(name);
    }
    private void fail(Exception exception) {
        failed.add(exception.toString());
        driver.getLogger().log(java.util.logging.Level.SEVERE, "阶段 4 真服断言失败", exception);
        finished.run();
    }
}
