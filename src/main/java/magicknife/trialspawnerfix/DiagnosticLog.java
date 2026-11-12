package magicknife.trialspawnerfix;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DiagnosticLog {
    private record Key(UUID world, String reason) {
    }
    private static final class Window {
        private long next;
        private long suppressed;
    }
    private final Map<Key, Window> windows = new HashMap<>();
    private final Logger logger;
    private final LongSupplier clock;
    private final Supplier<AuditJournal> audit;
    private long suppressed;

    DiagnosticLog(Logger logger, LongSupplier clock, Supplier<AuditJournal> audit) {
        this.logger = logger;
        this.clock = clock;
        this.audit = audit;
    }

    DiagnosticLog(Logger logger) {
        this(logger, System::nanoTime, () -> null);
    }

    synchronized void report(UUID world, String reason, String message, Throwable exception) {
        var window = windows.computeIfAbsent(new Key(world, reason), ignored -> new Window());
        long now = clock.getAsLong();
        if (window.next == 0 || now >= window.next) {
            logger.log(exception == null ? Level.WARNING : Level.SEVERE,
                    "event=" + reason + " world=" + world + " suppressed=" + window.suppressed + " " + message, exception);
            window.suppressed = 0;
            window.next = now + 60_000_000_000L;
            AuditJournal journal = audit.get();
            if (journal != null) {
                journal.diagnostic(world, reason);
            }
        } else {
            window.suppressed++;
            suppressed++;
        }
    }

    synchronized void removeWorld(UUID world) {
        windows.keySet().removeIf(key -> world.equals(key.world()));
    }

    synchronized long suppressed() {
        return suppressed;
    }
}
