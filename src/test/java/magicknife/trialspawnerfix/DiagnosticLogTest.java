package magicknife.trialspawnerfix;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DiagnosticLogTest {
    @Test
    void limitsByWorldAndReasonAndReportsSuppressionWithNextStackTrace() {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        var clock = new AtomicLong(1);
        var log = new DiagnosticLog(logger, clock::get, () -> null);
        UUID first = UUID.randomUUID();
        var error = new IllegalStateException("fixture failure");
        log.report(first, "read_failed", "坐标", error);
        log.report(first, "read_failed", "坐标", error);
        log.report(UUID.randomUUID(), "read_failed", "坐标", error);
        assertEquals(2, records.size());
        assertSame(error, records.getFirst().getThrown());
        clock.addAndGet(60_000_000_000L);
        log.report(first, "read_failed", "坐标", error);
        assertTrue(records.getLast().getMessage().contains("suppressed=1"));
        assertEquals(1, log.suppressed());
        log.removeWorld(first);
        log.report(first, "read_failed", "坐标", error);
        assertEquals(4, records.size());
    }
}
