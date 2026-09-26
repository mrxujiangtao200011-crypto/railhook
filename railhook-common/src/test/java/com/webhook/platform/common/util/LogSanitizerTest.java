package com.webhook.platform.common.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogSanitizerTest {

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("GET /api\nWARN  Organization deleted", "GET /api_WARN  Organization deleted"),
                Arguments.of("a\r\nb", "a__b"),
                Arguments.of("a\rb", "a_b"),
                Arguments.of("a\tb", "a_b"),
                Arguments.of("a\u0000b", "a_b"),
                Arguments.of("GET /api/v1/deliveries?limit=50", "GET /api/v1/deliveries?limit=50"),
                Arguments.of(null, null));
    }

    // A newline would let a request value forge a second log line; null must not become the word.
    @ParameterizedTest
    @MethodSource("values")
    void controlCharactersCannotForgeALogLine(String raw, String logged) {
        assertEquals(logged, LogSanitizer.forLog(raw));
    }
}
