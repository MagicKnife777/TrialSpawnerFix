package magicknife.trialspawnerfix;

import java.util.function.Supplier;
import java.util.logging.Logger;

public final class RepairService {
    public interface Target {
        boolean available();
        Snapshot read(PluginSettings settings);
        Long readEnd();
    }

    public interface Snapshot {
        CooldownPolicy.Input input();
        boolean writeEnd(long end);
    }

    public enum Result {
        UNAVAILABLE, REPLACED, SKIPPED, REPORTED, AUDIT_UNAVAILABLE, CONFIG_CHANGED,
        UPDATE_FAILED, READBACK_MISMATCH, REPAIRED, ERROR
    }

    record Configuration(PluginSettings settings, AuditJournal audit) {
    }

    private final Supplier<Configuration> configuration;
    private final DiagnosticLog diagnostics;

    RepairService(Supplier<Configuration> configuration, Logger logger) {
        this(configuration, new DiagnosticLog(logger));
    }

    RepairService(Supplier<Configuration> configuration, DiagnosticLog diagnostics) {
        this.configuration = configuration;
        this.diagnostics = diagnostics;
    }

    public Result check(Target target, AuditRecord.Context context) {
        long started = System.nanoTime();
        AuditJournal.Reservation reservation = null;
        Result result = Result.ERROR;
        try {
            Configuration published = configuration.get();
            if (published == null || !target.available()) {
                return Result.UNAVAILABLE;
            }
            PluginSettings current = published.settings();
            AuditJournal audit = published.audit();
            Snapshot snapshot = target.read(current);
            if (snapshot == null) {
                return Result.REPLACED;
            }
            CooldownPolicy.Input input = snapshot.input();
            CooldownPolicy.Decision decision = CooldownPolicy.evaluate(input);
            if (!decision.suspected()) {
                if (decision.reason() == CooldownPolicy.Reason.INVALID_INPUT
                        || decision.reason() == CooldownPolicy.Reason.ARITHMETIC_OVERFLOW) {
                    diagnostics.report(context.worldId(), "unrepairable", "reason=" + decision.reason() + " " + context, null);
                }
                return Result.SKIPPED;
            }
            if (decision.newEnd().isEmpty()) {
                if (audit != null) {
                    audit.report(context, input, decision);
                }
                return Result.REPORTED;
            }
            if (audit == null || !current.auditEnabled()
                    || (reservation = audit.reserve(context, input, decision)) == null) {
                return Result.AUDIT_UNAVAILABLE;
            }
            // 预留期间配置或生命周期改变时，不使用旧结论写入。
            if (!published.equals(configuration.get()) || !target.available()) {
                result = Result.CONFIG_CHANGED;
            } else if (!audit.healthy()) {
                result = Result.AUDIT_UNAVAILABLE;
            } else if (!snapshot.writeEnd(decision.newEnd().orElseThrow())) {
                result = Result.UPDATE_FAILED;
            } else {
                Long actual = target.readEnd();
                result = actual != null && actual.longValue() == decision.newEnd().orElseThrow()
                        ? Result.REPAIRED : Result.READBACK_MISMATCH;
            }
            if (result == Result.UPDATE_FAILED || result == Result.READBACK_MISMATCH) {
                diagnostics.report(context.worldId(), "repair_failed", "reason=" + result + " " + context,
                        new IllegalStateException("方块更新或立即读回失败"));
            }
            return result;
        } catch (RuntimeException exception) {
            result = Result.ERROR;
            diagnostics.report(context.worldId(), "check_failed", context.toString(), exception);
            return result;
        } finally {
            if (reservation != null) {
                reservation.finish(result.name().toLowerCase(java.util.Locale.ROOT),
                        result == Result.REPAIRED ? "repaired" : "failed", System.nanoTime() - started);
            }
        }
    }
}
