package com.webhook.platform.api.exception;

public class SignatureVerificationFailedException extends RuntimeException {

    public SignatureVerificationFailedException(String message) {
        super(message);
    }
}
