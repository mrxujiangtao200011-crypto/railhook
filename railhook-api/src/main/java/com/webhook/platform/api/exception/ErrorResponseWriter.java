package com.webhook.platform.api.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ErrorResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorResponseWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(toJson(ErrorResponse.of(code, message)));
    }

    public static byte[] toJsonBytes(ErrorResponse body) {
        return toJson(body).getBytes(StandardCharsets.UTF_8);
    }

    private static String toJson(ErrorResponse body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize an error response", e);
        }
    }
}
