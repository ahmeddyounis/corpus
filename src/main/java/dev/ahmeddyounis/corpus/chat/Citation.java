package dev.ahmeddyounis.corpus.chat;

import java.util.UUID;

/** A source the answer may cite as {@code [index]}. */
public record Citation(int index, UUID chunkId, UUID documentId, String filename, int chunkIndex,
                       double rrfScore) {
}
