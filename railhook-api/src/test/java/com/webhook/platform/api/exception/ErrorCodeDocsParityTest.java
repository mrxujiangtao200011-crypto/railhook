package com.webhook.platform.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ratchet")
class ErrorCodeDocsParityTest {

    private static final Path DOCS = Paths.get("..", "railhook-docs", "src", "content", "docs");

    private static final Pattern ROW = Pattern.compile("(?m)^\\|\\s*`(\\d{3}|[45]xx)`\\s*\\|([^|]*)\\|");
    private static final Pattern CODE = Pattern.compile("`([a-z_]+)`");

    @ParameterizedTest
    @ValueSource(strings = {"platform/errors-limits.mdx", "uk/platform/errors-limits.mdx"})
    @DisplayName("the errors page lists every ErrorCode at its status, and nothing else")
    void docsTableMatchesErrorCode(String page) throws IOException {
        Path file = DOCS.resolve(page);
        Map<String, String> documented = documentedCodes(file);
        assertFalse(documented.isEmpty(), file + " has no error-code table rows — has the format changed?");

        Map<String, ErrorCode> byValue = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(ErrorCode::getValue, c -> c));

        Set<String> missing = new TreeSet<>(byValue.keySet());
        missing.removeAll(documented.keySet());
        Set<String> unknown = new TreeSet<>(documented.keySet());
        unknown.removeAll(byValue.keySet());
        List<String> wrongStatus = new ArrayList<>();
        documented.forEach((value, status) -> {
            ErrorCode code = byValue.get(value);
            if (code != null && !statusMatches(status, code)) {
                wrongStatus.add(value + " is listed under " + status + " but ErrorCode sends "
                        + code.getStatus().value());
            }
        });

        assertTrue(missing.isEmpty() && unknown.isEmpty() && wrongStatus.isEmpty(),
                "The error-code table in " + file + " has drifted from ErrorCode, the codes the API "
                        + "actually sends. Clients branch on these values, so the table is the contract.\n"
                        + "  In ErrorCode, missing from the table: " + missing + "\n"
                        + "  In the table, not in ErrorCode:       " + unknown + "\n"
                        + "  Listed under the wrong status:         " + wrongStatus + "\n"
                        + "Add or fix the row in both the English and the Ukrainian page, or, if the "
                        + "table is right, change ErrorCode.");
    }

    private static boolean statusMatches(String documented, ErrorCode code) {
        int actual = code.getStatus().value();
        if (documented.endsWith("xx")) {
            return actual / 100 == documented.charAt(0) - '0';
        }
        return Integer.parseInt(documented) == actual;
    }

    private static Map<String, String> documentedCodes(Path file) throws IOException {
        String page = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> codes = new LinkedHashMap<>();
        Matcher row = ROW.matcher(page);
        while (row.find()) {
            Matcher code = CODE.matcher(row.group(2));
            while (code.find()) {
                codes.put(code.group(1), row.group(1));
            }
        }
        return codes;
    }
}
