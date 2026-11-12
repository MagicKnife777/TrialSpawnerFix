package magicknife.trialspawnerfix;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

public record PluginSettings(CooldownPolicy.Strategy strategy, Set<UUID> worlds, long margin,
                             long generation, boolean auditEnabled, int auditCapacity,
                             int candidateLimit) {
    public PluginSettings {
        worlds = Set.copyOf(worlds);
    }

    public boolean enables(UUID world) {
        return worlds.isEmpty() || worlds.contains(world);
    }

    public static PluginSettings parse(ConfigurationSection config, long generation) {
        integer(config, "schema-version", 1, 1, 1);
        Object rawStrategy = value(config, "strategy", "report-only");
        if (!(rawStrategy instanceof String strategy)) {
            throw new IllegalArgumentException("strategy 必须为字符串");
        }
        Object rawWorlds = value(config, "enabled-worlds", List.of());
        if (!(rawWorlds instanceof List<?> list)) {
            throw new IllegalArgumentException("enabled-worlds 必须为 UUID 列表");
        }
        Set<UUID> worlds = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("enabled-worlds 只能包含 UUID 字符串");
            }
            try {
                UUID uuid = UUID.fromString(text);
                if (!uuid.toString().equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("UUID 格式不完整");
                }
                worlds.add(uuid);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("enabled-worlds 存在非法 UUID：" + text, exception);
            }
        }
        return new PluginSettings(CooldownPolicy.Strategy.parse(strategy), worlds,
                integer(config, "tolerance-ticks", 200, 0, 1_200_000), generation,
                bool(config, "audit.enabled", true),
                (int) integer(config, "audit.queue-capacity", 8192, 2, 65_536),
                (int) integer(config, "max-candidate-buffer-per-chunk", 4096, 1, 65_536));
    }

    private static Object value(ConfigurationSection config, String path, Object fallback) {
        Object value = config.get(path);
        return value == null ? fallback : value;
    }

    private static long integer(ConfigurationSection config, String path, long fallback, long min, long max) {
        Object raw = value(config, path, fallback);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(path + " 必须为整数，允许范围 " + min + ".." + max);
        }
        long number = ((Number) raw).longValue();
        if (number < min || number > max) {
            throw new IllegalArgumentException(path + " 允许范围 " + min + ".." + max);
        }
        return number;
    }

    private static boolean bool(ConfigurationSection config, String path, boolean fallback) {
        Object raw = value(config, path, fallback);
        if (!(raw instanceof Boolean result)) {
            throw new IllegalArgumentException(path + " 必须为布尔值");
        }
        return result;
    }
}
