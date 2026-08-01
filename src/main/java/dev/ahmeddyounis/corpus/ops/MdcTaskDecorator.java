package dev.ahmeddyounis.corpus.ops;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Carries MDC across the hop onto the executors' virtual threads.
 *
 * <p>Without this the correlation id is missing from exactly the log lines that
 * matter most: ingestion and chat both hand off to another thread immediately, so
 * every line they emit — including the failures — would be unattributable.
 */
@Component
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        if (submitterContext == null) {
            return runnable;
        }
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(submitterContext);
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
