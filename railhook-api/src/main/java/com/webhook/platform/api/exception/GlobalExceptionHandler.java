package com.webhook.platform.api.exception;

import com.webhook.platform.common.exception.InvalidUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        
        String summary = fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", summary);
        
        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorCode.VALIDATION_ERROR.getValue())
                .message("Invalid request parameters")
                .status(ErrorCode.VALIDATION_ERROR.getStatus().value())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex) {
        log.warn("Response status exception: {} {}", ex.getStatusCode(), ex.getReason());
        ErrorResponse error = new ErrorResponse(
                (ex.getStatusCode().is4xxClientError() ? ErrorCode.CLIENT_ERROR : ErrorCode.SERVER_ERROR).getValue(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                ex.getStatusCode().value()
        );
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, WebRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("{} {}: {}", code.getStatus().value(), code.getValue(), ex.getMessage());
        return respond(code, ex.getMessage());
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceededException(
            QuotaExceededException ex, WebRequest request) {
        log.warn("Quota exceeded: {} (plan={})", ex.getQuotaName(), ex.getPlanName());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("quota", ex.getQuotaName());
        details.put("current", String.valueOf(ex.getCurrentUsage()));
        details.put("limit", String.valueOf(ex.getLimit()));
        details.put("plan", ex.getPlanName());
        ErrorResponse error = ErrorResponse.builder()
                .error(ErrorCode.QUOTA_EXCEEDED.getValue())
                .message(ex.getMessage())
                .status(ErrorCode.QUOTA_EXCEEDED.getStatus().value())
                .fieldErrors(details)
                .build();
        return ResponseEntity.status(ErrorCode.QUOTA_EXCEEDED.getStatus()).body(error);
    }

    /**
     * Usually two requests racing past the service's pre-check. The message carries the SQL, so
     * it stays in the log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(ErrorCode.CONFLICT, "The request conflicts with the current state of the resource");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(
            PropertyReferenceException ex, WebRequest request) {
        log.debug("Unknown property reference: {}", ex.getPropertyName());
        return respond(ErrorCode.INVALID_PARAMETER, "Cannot sort by '" + ex.getPropertyName() + "'");
    }

    /** Actuator lives on the management port, so /actuator/health on this port lands here. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, WebRequest request) {
        log.debug("No handler for {}", ex.getResourcePath());
        return respond(ErrorCode.NOT_FOUND, "The requested resource was not found");
    }

    /** Requests Spring rejects before a controller must not fall to the 500 catch-all, or clients retry them. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        log.debug("Method {} not supported for this path", ex.getMethod());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus());
        // RFC 9110 requires Allow on a 405.
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(new HttpMethod[0]));
        }
        return response.body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED,
                "The " + ex.getMethod() + " method is not supported for this endpoint"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, WebRequest request) {
        // Not ex.getMessage(): Jackson quotes part of the payload, which can hold credentials.
        log.debug("Unreadable request body: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return respond(ErrorCode.MALFORMED_REQUEST, "The request body is missing or is not valid JSON");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        log.debug("Unsupported content type: {}", ex.getContentType());
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "This endpoint accepts application/json");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, WebRequest request) {
        return respond(ErrorCode.MISSING_PARAMETER, "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER, "Parameter '" + ex.getName() + "' is not in the expected format");
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrlException(
            InvalidUrlException ex, WebRequest request) {
        log.warn("Rejected webhook URL: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_URL, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, WebRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message));
    }
}
