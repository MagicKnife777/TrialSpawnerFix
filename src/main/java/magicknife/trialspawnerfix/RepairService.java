package magicknife.trialspawnerfix;

import java.util.function.Supplier;
import java.util.logging.Level;
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

    private final Supplier<PluginSettings> settings;
    private final AuditJournal audit;
    private final Logger logger;

    RepairService(Supplier<PluginSettings> settings, AuditJournal audit, Logger logger) {
        this.settings = settings;
        this.audit = audit;
        this.logger = logger;
    }

    public Result check(Target target, AuditRecord.Context context) {
        long started = System.nanoTime();
        AuditJournal.Reservation reservation = null;
        Result result = Result.ERROR;
        try {
            PluginSettings current = settings.get();
            if (current == null || !target.available()) {
                return Result.UNAVAILABLE;
            }
            Snapshot snapshot = target.read(current);
            if (snapshot == null) {
                return Result.REPLACED;
            }
            CooldownPolicy.Input input = snapshot.input();
            CooldownPolicy.Decision decision = CooldownPolicy.evaluate(input);
            if (!decision.suspected()) {
                if (decision.reason() == CooldownPolicy.Reason.INVALID_INPUT
                        || decision.reason() == CooldownPolicy.Reason.ARITHMETIC_OVERFLOW) {
                    logger.warning("event=unrepairable reason=" + decision.reason() + " " + context);
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
            if (settings.get() != current || !target.available()) {
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
                logger.severe("event=repair_failed reason=" + result + " " + context);
            }
            return result;
        } catch (RuntimeException exception) {
            result = Result.ERROR;
            logger.log(Level.SEVERE, "event=check_failed reason=exception " + context, exception);
            return result;
        } finally {
            if (reservation != null) {
                reservation.finish(result.name().toLowerCase(java.util.Locale.ROOT),
                        result == Result.REPAIRED ? "repaired" : "failed", System.nanoTime() - started);
            }
        }
    }
}
