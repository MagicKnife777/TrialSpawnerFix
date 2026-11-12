package magicknife.trialspawnerfix;

import java.util.UUID;

record CommandRequest(Kind kind, UUID world, CheckCoordinator.Position point, Area area,
                      CooldownPolicy.Strategy strategy) {
    enum Kind {
        STATUS("inspect"), INSPECT("inspect"), LOADED("scan"), AREA("scan"), REPAIR("repair"), RELOAD("reload");
        private final String permission;
        Kind(String permission) {
            this.permission = "trialspawnerfix." + permission;
        }
        String permission() {
            return permission;
        }
    }

    record Area(int x, int z, int radius) {
        boolean contains(int chunkX, int chunkZ) {
            return Math.abs((long) chunkX - x) <= radius && Math.abs((long) chunkZ - z) <= radius;
        }
    }

    static CommandRequest parse(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("status"))) {
            return new CommandRequest(Kind.STATUS, null, null, null, null);
        }
        if (args.length == 1 && args[0].equals("reload")) {
            return new CommandRequest(Kind.RELOAD, null, null, null, null);
        }
        if ((args[0].equals("inspect") && args.length == 5) || (args[0].equals("repair") && args.length == 6)) {
            boolean inspect = args[0].equals("inspect");
            var point = new CheckCoordinator.Position(integer(args[2]), integer(args[3]), integer(args[4]));
            if (Math.abs((long) point.x()) > 30_000_000 || Math.abs((long) point.z()) > 30_000_000) {
                throw new IllegalArgumentException("方块坐标超出世界边界。");
            }
            var strategy = inspect ? CooldownPolicy.Strategy.REPORT_ONLY : CooldownPolicy.Strategy.parse(args[5]);
            if (!inspect && strategy == CooldownPolicy.Strategy.REPORT_ONLY) {
                throw new IllegalArgumentException("repair 策略必须为 restart-cooldown 或 expire-now。");
            }
            return new CommandRequest(inspect ? Kind.INSPECT : Kind.REPAIR, uuid(args[1]), point, null, strategy);
        }
        if (args[0].equals("scan") && args.length >= 2) {
            if (args[1].equals("loaded") && (args.length == 2 || args.length == 3)) {
                return new CommandRequest(Kind.LOADED, args.length == 3 ? uuid(args[2]) : null, null, null, null);
            }
            if (args[1].equals("area") && args.length == 6) {
                int radius = integer(args[5]);
                if (radius < 0 || radius > 32) {
                    throw new IllegalArgumentException("radius 允许范围 0..32。");
                }
                var area = new Area(integer(args[3]), integer(args[4]), radius);
                if (Math.abs((long) area.x()) > 1_875_000 || Math.abs((long) area.z()) > 1_875_000) {
                    throw new IllegalArgumentException("区块坐标超出世界边界。");
                }
                return new CommandRequest(Kind.AREA, uuid(args[2]), null, area, null);
            }
        }
        throw new IllegalArgumentException("用法：status | inspect <UUID> <x> <y> <z> | scan loaded [UUID] | scan area <UUID> <chunk-x> <chunk-z> <radius> | repair <UUID> <x> <y> <z> <restart-cooldown|expire-now> | reload");
    }

    private static UUID uuid(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("请使用完整世界 UUID。");
        }
        return id;
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("坐标和半径必须为整数：" + text, exception);
        }
    }
}
