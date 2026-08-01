package dev.ahmeddyounis.corpus.ingestion;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * On SIGTERM, after the executors have drained, fail whatever this instance was
 * still working on so the outcome is deterministic and instance-local rather than
 * left for the next startup to guess at.
 *
 * <p>Runs in a late shutdown phase (lifecycle phases stop in descending order, so a
 * low phase stops last) and delegates to the same owner-scoped sweep the startup
 * backstop uses — it can only ever touch this instance's own rows.
 */
@Component
public class IngestionShutdownSweeper implements SmartLifecycle {

    private final StaleIngestionSweeper sweeper;
    private volatile boolean running;

    public IngestionShutdownSweeper(StaleIngestionSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        sweeper.sweep();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Stop after the web server and the executors, while the datasource is alive.
        return Integer.MIN_VALUE + 1;
    }
}
