package magicknife.trialspawnerfix;

import java.time.Instant;
import java.util.UUID;

public record AuditRecord(int schemaVersion, String utcTime, UUID runId, long eventId, long attemptId,
                          long scanId, String source, String actor, String pluginVersion,
                          String serverVersion, UUID worldId, String worldName, int x, int y, int z,
                          long loadGeneration, long configGeneration, String stage, boolean ominous,
                          long gameTime, long oldEnd, long length, Long remaining, Long newEnd,
                          String strategy, String event, String reason, String result, long elapsedNanos) {
    public record Context(UUID worldId, String worldName, int x, int y, int z,
                          long loadGeneration, long scanId, String source, String actor) {
    }

    public static AuditRecord create(UUID runId, long id, long attemptId, String pluginVersion,
                                     String serverVersion, Context context, CooldownPolicy.Input input,
                                     CooldownPolicy.Decision decision, String event, String reason,
                                     String result, long elapsedNanos) {
        return new AuditRecord(1, Instant.now().toString(), runId, id, attemptId, context.scanId(),
                context.source(), context.actor(), pluginVersion, serverVersion, context.worldId(),
                context.worldName(), context.x(), context.y(), context.z(), context.loadGeneration(),
                input.configGeneration(), input.stage().name(), input.ominous(), input.gameTime(),
                input.cooldownEnd(), input.cooldownLength(),
                decision.remaining().isPresent() ? decision.remaining().getAsLong() : null,
                decision.newEnd().isPresent() ? decision.newEnd().getAsLong() : null,
                input.strategy().configValue(), event, reason, result, elapsedNanos);
    }
}
