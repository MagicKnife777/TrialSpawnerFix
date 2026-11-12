package magicknife.trialspawnerfix;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuditJournal implements AutoCloseable {
    interface Sink extends AutoCloseable {
        void write(AuditRecord record) throws IOException;
        @Override
        void close() throws IOException;
    }

    private final ArrayDeque<AuditRecord> queue = new ArrayDeque<>();
    private final int capacity;
    private final Sink sink;
    private final Logger logger;
    private final Thread writer;
    private final AtomicLong ids = new AtomicLong();
    private final UUID runId = UUID.randomUUID();
    private final String pluginVersion;
    private final String serverVersion;
    private int reservedResults;
    private int writing;
    private long dropped;
    private boolean accepting = true;
    private volatile boolean healthy = true;

    AuditJournal(int capacity, Sink sink, Logger logger, String pluginVersion, String serverVersion) {
        this.capacity = capacity;
        this.sink = sink;
        this.logger = logger;
        this.pluginVersion = pluginVersion;
        this.serverVersion = serverVersion;
        writer = new Thread(this::drain, "TrialSpawnerFix-audit");
        writer.setDaemon(true);
        writer.start();
    }

    public synchronized Reservation reserve(AuditRecord.Context context, CooldownPolicy.Input input,
                                             CooldownPolicy.Decision decision) {
        if (!accepting || !healthy || queue.size() + reservedResults + 2 > capacity) {
            return null;
        }
        long attempt = ids.incrementAndGet();
        queue.add(record(attempt, attempt, context, input, decision, "repair_attempt", "suspected", "attempt", 0));
        reservedResults++;
        notifyAll();
        return new Reservation(attempt, context, input, decision);
    }

    public synchronized void report(AuditRecord.Context context, CooldownPolicy.Input input,
                                    CooldownPolicy.Decision decision) {
        if (!accepting || !healthy || queue.size() + reservedResults >= capacity) {
            dropped++;
            return;
        }
        queue.add(record(ids.incrementAndGet(), 0, context, input, decision, "suspected",
                "suspected", "report-only", 0));
        notifyAll();
    }

    public boolean healthy() {
        return healthy;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public final class Reservation {
        private final long attempt;
        private final AuditRecord.Context context;
        private final CooldownPolicy.Input input;
        private final CooldownPolicy.Decision decision;
        private boolean finished;

        private Reservation(long attempt, AuditRecord.Context context, CooldownPolicy.Input input,
                            CooldownPolicy.Decision decision) {
            this.attempt = attempt;
            this.context = context;
            this.input = input;
            this.decision = decision;
        }

        public void finish(String reason, String result, long elapsedNanos) {
            synchronized (AuditJournal.this) {
                if (finished) {
                    throw new IllegalStateException("修复审计结果重复提交");
                }
                finished = true;
                reservedResults--;
                if (healthy) {
                    queue.add(record(ids.incrementAndGet(), attempt, context, input, decision,
                            "repair_result", reason, result, elapsedNanos));
                } else {
                    dropped++;
                    logger.severe("event=audit_result_lost attempt_id=" + attempt + " 审计故障导致结果无法接收。");
                }
                AuditJournal.this.notifyAll();
            }
        }
    }

    private AuditRecord record(long id, long attempt, AuditRecord.Context context, CooldownPolicy.Input input,
                               CooldownPolicy.Decision decision, String event, String reason,
                               String result, long elapsed) {
        return AuditRecord.create(runId, id, attempt, pluginVersion, serverVersion, context,
                input, decision, event, reason, result, elapsed);
    }

    private void drain() {
        try {
            while (true) {
                AuditRecord next;
                synchronized (this) {
                    while (queue.isEmpty() && (accepting || reservedResults > 0)) {
                        wait();
                    }
                    if (queue.isEmpty()) {
                        return;
                    }
                    next = queue.remove();
                    writing = 1;
                }
                sink.write(next);
                synchronized (this) {
                    writing = 0;
                }
            }
        } catch (IOException | RuntimeException exception) {
            fail(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        } finally {
            try {
                sink.close();
            } catch (IOException | RuntimeException exception) {
                fail(exception);
            }
        }
    }

    private synchronized void fail(Exception exception) {
        healthy = false;
        accepting = false;
        dropped += queue.size() + writing;
        queue.clear();
        writing = 0;
        logger.log(Level.SEVERE, "event=audit_failed 自动修改已暂停，重启插件前须排查审计故障。", exception);
        notifyAll();
    }

    @Override
    public void close() {
        synchronized (this) {
            accepting = false;
            notifyAll();
        }
        try {
            writer.join(2000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "event=audit_shutdown_interrupted 审计排空被中断。", exception);
        }
        synchronized (this) {
            if (writer.isAlive()) {
                healthy = false;
                logger.warning("event=audit_undrained count=" + (queue.size() + reservedResults + writing)
                        + " 审计未在 2 秒内排空。");
                writer.interrupt();
            }
            if (dropped > 0) {
                logger.warning("event=audit_dropped count=" + dropped);
            }
        }
    }
}
