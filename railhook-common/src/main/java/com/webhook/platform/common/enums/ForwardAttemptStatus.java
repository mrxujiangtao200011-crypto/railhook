package com.webhook.platform.common.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** One row per Attempt: a retry finalises this row and inserts its successor PENDING. */
public enum ForwardAttemptStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DLQ,
    /** A Transformation chose not to send. Terminal, and not the DLQ: there is nothing to fix. */
    CANCELLED;

    private static final Map<ForwardAttemptStatus, Set<ForwardAttemptStatus>> NEXT =
            new EnumMap<>(ForwardAttemptStatus.class);

    static {
        NEXT.put(PENDING, EnumSet.of(PENDING, PROCESSING, DLQ));
        NEXT.put(PROCESSING, EnumSet.of(PENDING, PROCESSING, SUCCESS, FAILED, DLQ, CANCELLED));
        NEXT.put(SUCCESS, EnumSet.noneOf(ForwardAttemptStatus.class));
        NEXT.put(FAILED, EnumSet.noneOf(ForwardAttemptStatus.class));
        NEXT.put(DLQ, EnumSet.of(FAILED));
        NEXT.put(CANCELLED, EnumSet.noneOf(ForwardAttemptStatus.class));
    }

    public boolean canMoveTo(ForwardAttemptStatus next) {
        return NEXT.get(this).contains(next);
    }

    public ForwardAttemptStatus moveTo(ForwardAttemptStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStateException("A Forward attempt cannot go from " + this + " to " + next);
        }
        return next;
    }
}
