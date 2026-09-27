package com.webhook.platform.worker.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Where the next claim resumes, so every target gets its turn before any gets a second. */
final class ClaimCursor {

    static final UUID START = new UUID(0L, 0L);

    // Postgres orders uuid as unsigned bytes; UUID.compareTo compares signed longs.
    private static final Comparator<UUID> POSTGRES_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private ClaimCursor() {
    }

    static UUID next(List<UUID> claimedTargets, int limit) {
        if (claimedTargets.size() < limit) {
            return START;
        }
        return claimedTargets.stream().max(POSTGRES_ORDER).orElse(START);
    }
}
