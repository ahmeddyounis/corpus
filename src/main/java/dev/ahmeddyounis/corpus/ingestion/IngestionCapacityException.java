package dev.ahmeddyounis.corpus.ingestion;

/**
 * The ingestion bulkhead is saturated. Deliberately load shedding rather than
 * queueing without bound; the caller should retry shortly.
 */
public class IngestionCapacityException extends RuntimeException {

    public IngestionCapacityException(String message) {
        super(message);
    }
}
