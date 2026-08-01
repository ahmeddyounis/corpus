package dev.ahmeddyounis.corpus.ops;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * A stable identifier for this application instance, used to tell "work this
 * instance started" apart from "work another live instance is doing".
 *
 * <p>The resolution order matters: Kubernetes keeps {@code HOSTNAME} and Fly keeps
 * {@code FLY_MACHINE_ID} across a container restart-in-place, so a restarted
 * instance recognises its own interrupted work immediately. A rescheduled pod gets
 * a fresh identity and is handled by the sweeper's age-based fallback instead.
 */
@Component
public class InstanceIdentity {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdentity.class);
    private static final int MAX_LENGTH = 64;

    private final String id;

    public InstanceIdentity(Environment environment) {
        this.id = resolve(environment);
        log.info("Instance identity: {}", id);
    }

    public String id() {
        return id;
    }

    private static String resolve(Environment environment) {
        for (String key : new String[]{"CORPUS_INSTANCE_ID", "FLY_MACHINE_ID", "HOSTNAME"}) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return truncate(value.strip());
            }
        }
        return truncate(UUID.randomUUID().toString());
    }

    private static String truncate(String value) {
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}
