package com.webhook.platform.api.exception;

public class SourceDisabledException extends RuntimeException {
    public SourceDisabledException(String message) { super(message); }
}
