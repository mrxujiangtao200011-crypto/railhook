package com.webhook.platform.api.exception;

import lombok.Getter;

@Getter
public class PublicBinLimitException extends RuntimeException {

    private final boolean overall;

    public PublicBinLimitException(boolean overall, String message) {
        super(message);
        this.overall = overall;
    }
}
