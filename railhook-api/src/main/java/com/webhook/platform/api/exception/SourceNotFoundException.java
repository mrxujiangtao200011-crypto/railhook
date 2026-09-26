package com.webhook.platform.api.exception;

public class SourceNotFoundException extends RuntimeException {
    public SourceNotFoundException(String message) { super(message); }
}
