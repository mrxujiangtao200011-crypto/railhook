package com.webhook.platform.api.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR("validation_error", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("malformed_request", HttpStatus.BAD_REQUEST),
    MISSING_PARAMETER("missing_parameter", HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER("invalid_parameter", HttpStatus.BAD_REQUEST),
    INVALID_URL("invalid_url", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST("invalid_request", HttpStatus.BAD_REQUEST),
    CAPTCHA_FAILED("captcha_failed", HttpStatus.BAD_REQUEST),

    UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED),
    SIGNATURE_VERIFICATION_FAILED("signature_verification_failed", HttpStatus.UNAUTHORIZED),

    QUOTA_EXCEEDED("quota_exceeded", HttpStatus.PAYMENT_REQUIRED),

    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN),
    DEMO_READ_ONLY("demo_read_only", HttpStatus.FORBIDDEN),
    REAUTHENTICATION_REQUIRED("reauthentication_required", HttpStatus.FORBIDDEN),
    SUSPENDED("suspended", HttpStatus.FORBIDDEN),
    TUNNEL_SUSPENDED("tunnel_suspended", HttpStatus.FORBIDDEN),

    NOT_FOUND("not_found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("method_not_allowed", HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT("conflict", HttpStatus.CONFLICT),
    GONE("gone", HttpStatus.GONE),
    DISABLED("disabled", HttpStatus.GONE),
    PAYLOAD_TOO_LARGE("payload_too_large", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    ACCOUNT_LOCKED("account_locked", HttpStatus.LOCKED),

    RATE_LIMIT_EXCEEDED("rate_limit_exceeded", HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS),
    ORGANIZATION_RATE_LIMIT("organization_rate_limit", HttpStatus.TOO_MANY_REQUESTS),
    PLATFORM_RATE_LIMIT("platform_rate_limit", HttpStatus.TOO_MANY_REQUESTS),
    PORTAL_RATE_LIMIT("portal_rate_limit", HttpStatus.TOO_MANY_REQUESTS),
    TOO_MANY_ACTIVE_URLS("too_many_active_urls", HttpStatus.TOO_MANY_REQUESTS),
    CONTACT_BUSY("contact_busy", HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR),
    TUNNEL_ERROR("tunnel_error", HttpStatus.BAD_GATEWAY),
    SERVICE_UNAVAILABLE("service_unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    // A CDN replaces a 502 with its own page, and providers retry a 503.
    TUNNEL_OFFLINE("tunnel_offline", HttpStatus.SERVICE_UNAVAILABLE),
    TESTER_BUSY("tester_busy", HttpStatus.SERVICE_UNAVAILABLE),
    CONTACT_UNAVAILABLE("contact_unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    TUNNEL_TIMEOUT("tunnel_timeout", HttpStatus.GATEWAY_TIMEOUT),

    AUTHORIZATION_PENDING("authorization_pending", HttpStatus.ACCEPTED),

    CLIENT_ERROR("client_error", HttpStatus.BAD_REQUEST),
    SERVER_ERROR("server_error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String value;
    private final HttpStatus status;
}
