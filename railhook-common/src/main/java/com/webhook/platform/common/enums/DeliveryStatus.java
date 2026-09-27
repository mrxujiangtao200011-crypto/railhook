package com.webhook.platform.common.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DLQ,
    /** A Transformation chose not to send. Terminal, and not the DLQ: there is nothing to fix. */
    CANCELLED;

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> NEXT = new EnumMap<>(DeliveryStatus.class);

    static {
        NEXT.put(PENDING, EnumSet.of(PENDING, PROCESSING, DLQ));
        NEXT.put(PROCESSING, EnumSet.of(PENDING, PROCESSING, SUCCESS, FAILED, DLQ, CANCELLED));
        NEXT.put(SUCCESS, EnumSet.noneOf(DeliveryStatus.class));
        NEXT.put(FAILED, EnumSet.of(PENDING));
        NEXT.put(DLQ, EnumSet.of(PENDING));
        NEXT.put(CANCELLED, EnumSet.of(PENDING));
    }

    public boolean canMoveTo(DeliveryStatus next) {
        return NEXT.get(this).contains(next);
    }

    public DeliveryStatus moveTo(DeliveryStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStateException("A Delivery cannot go from " + this + " to " + next);
        }
        return next;
    }
}
