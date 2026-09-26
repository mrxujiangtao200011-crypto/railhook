package com.webhook.platform.api.exception;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) { super(message); }
}
