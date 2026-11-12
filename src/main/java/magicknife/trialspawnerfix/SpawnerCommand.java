package magicknife.trialspawnerfix;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class SpawnerCommand implements CommandExecutor {
    private final TrialSpawnerFixPlugin plugin;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    SpawnerCommand(TrialSpawnerFixPlugin plugin) {
        this.plugin = plugin;
    }

    private final class Reply {
        private final UUID player;
        private final String actor;

        private Reply(CommandSender sender) {
            player = sender instanceof Player person ? person.getUniqueId() : null;
            actor = player == null ? "console" : player.toString();
        }

        private void send(String message) {
            if (!plugin.isEnabled()) {
                return;
            }
            if (player == null) {
                Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> Bukkit.getConsoleSender().sendMessage(message));
            } else {
                Player target = Bukkit.getPlayer(player);
                if (target != null) {
                    target.getScheduler().run(plugin, ignored -> target.sendMessage(message), null);
                }
            }
        }

        private void finish(String message) {
            pending.remove(actor);
            send(message);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandRequest request;
        try {
            request = CommandRequest.parse(args);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }
        if (!sender.hasPermission(request.kind().permission())) {
            sender.sendMessage("没有权限：" + request.kind().permission());
            return true;
        }
        var reply = new Reply(sender);
        if (request.kind() == CommandRequest.Kind.STATUS) {
            sender.sendMessage(plugin.status());
            return true;
        }
        if (!pending.add(reply.actor)) {
            sender.sendMessage("上一请求尚未完成；可以使用 status 查看积压。");
            return true;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> execute(request, reply));
        return true;
    }

    private void execute(CommandRequest request, Reply reply) {
        try {
            if (request.kind() == CommandRequest.Kind.RELOAD) {
                reply.send("配置重载已排队。");
                plugin.reloadSettings(reply::finish);
                return;
            }
            if (request.kind() == CommandRequest.Kind.LOADED && request.world() == null) {
                var worlds = Bukkit.getWorlds().stream().filter(world -> plugin.settings().enables(world.getUID())).toList();
                if (worlds.isEmpty()) {
                    throw new IllegalArgumentException("没有已启用的世界。");
                }
                var remaining = new java.util.concurrent.atomic.AtomicInteger(worlds.size());
                for (var world : worlds) {
                    try {
                        long id = plugin.scans().request(world, CheckCoordinator.Source.ADMIN, null, reply.actor, message -> {
                            reply.send(message);
                            if (remaining.decrementAndGet() == 0) {
                                reply.finish("全部已加载世界扫描已完成。");
                            }
                        });
                        reply.send("扫描已排队 scan_id=" + id + " world=" + world.getUID());
                    } catch (IllegalArgumentException exception) {
                        reply.send("world=" + world.getUID() + " " + exception.getMessage());
                        if (remaining.decrementAndGet() == 0) {
                            reply.finish("全部世界请求已处理；请查看各世界扫描结果。");
                        }
                    }
                }
                return;
            }
            var world = Bukkit.getWorld(request.world());
            if (world == null) {
                throw new IllegalArgumentException("世界不存在：" + request.world());
            }
            if (request.kind() == CommandRequest.Kind.LOADED || request.kind() == CommandRequest.Kind.AREA) {
                long id = plugin.scans().request(world, CheckCoordinator.Source.ADMIN, request.area(), reply.actor, reply::finish);
                reply.send("扫描已排队 scan_id=" + id);
                return;
            }
            if (request.point().y() < world.getMinHeight() || request.point().y() >= world.getMaxHeight()) {
                throw new IllegalArgumentException("Y 坐标允许范围 " + world.getMinHeight() + ".." + (world.getMaxHeight() - 1));
            }
            var key = new CheckCoordinator.Key(request.world(), request.point().x() >> 4, request.point().z() >> 4);
            var point = new CheckCoordinator.Point(request.point(), request.strategy(), reply.actor, reply::finish);
            if (!plugin.coordinator().point(key, point)) {
                throw new IllegalArgumentException("检查容量已满，请稍后重试。");
            }
            reply.send("单点检查已排队；完成后返回结果。");
        } catch (IllegalArgumentException exception) {
            reply.finish(exception.getMessage());
        } catch (RuntimeException exception) {
            plugin.warn(request.world(), "command_failed", "kind=" + request.kind(), exception);
            reply.finish("命令执行失败，请查看服务器日志。");
        }
    }
}
