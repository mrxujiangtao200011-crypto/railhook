package com.webhook.platform.api.ops;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// A green test that checks nothing is worse than no test: it once hid that developers could add members.
@Tag("ratchet")
class TestsAssertSomethingTest {

    private static final List<Path> TEST_ROOTS = List.of(
            Path.of("..", "railhook-common", "src", "test", "java"),
            Path.of("..", "railhook-api", "src", "test", "java"),
            Path.of("..", "railhook-worker", "src", "test", "java"),
            Path.of("..", "railhook-cli", "src", "test", "java"));

    private static final Pattern LANGUAGE_ASSERT = Pattern.compile("(?m)^\\s*assert[\\s(]");

    private static final Pattern TEST_METHOD = Pattern.compile(
            "@(?:Test|ParameterizedTest)\\b[^{;]*?\\bvoid\\s+(\\w+)\\s*\\([^)]*\\)[^{;]*\\{");

    private static final Pattern CHECK = Pattern.compile(
            "\\b(?:assert\\w*|verify\\w*|expect\\w*|fail|then\\w*|StepVerifier|await\\w*)\\s*[(.<]"
                    + "|\\.(?:is|has|contains|doesNot|satisfies|matches|containsExactly|extracting)\\w*\\(");

    @Test
    void noJavaAssertKeyword() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : testFiles()) {
            String code = stripLiteralsAndComments(Files.readString(file));
            Matcher m = LANGUAGE_ASSERT.matcher(code);
            while (m.find()) {
                offenders.add(file.getFileName() + ":" + lineOf(code, m.start()));
            }
        }
        assertThat(offenders)
                .as("`assert` is skipped without -ea, e.g. in an IDE run; use AssertJ's assertThat")
                .isEmpty();
    }

    @Test
    void everyTestChecksSomething() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : testFiles()) {
            String code = stripLiteralsAndComments(Files.readString(file));
            Matcher m = TEST_METHOD.matcher(code);
            while (m.find()) {
                if (!CHECK.matcher(body(code, m.end())).find()) {
                    offenders.add(file.getFileName() + "#" + m.group(1));
                }
            }
        }
        assertThat(offenders)
                .as("these tests assert, verify or expect nothing, so they pass whatever the code does")
                .isEmpty();
    }

    private static List<Path> testFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : TEST_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }

    private static String body(String code, int openBraceEnd) {
        int depth = 1;
        int i = openBraceEnd;
        while (depth > 0 && i < code.length()) {
            char c = code.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return code.substring(openBraceEnd, i);
    }

    // Braces and keywords inside strings, text blocks and comments would throw the scan off.
    private static String stripLiteralsAndComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            if (code.startsWith("\"\"\"", i)) {
                int end = code.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? code.length() : end + 3;
                out.append("\"\"").append(newlinesIn(code, i, stop));
                i = stop;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                int stop = end < 0 ? code.length() : end + 2;
                out.append(newlinesIn(code, i, stop));
                i = stop;
            } else if (code.charAt(i) == '"' || code.charAt(i) == '\'') {
                char quote = code.charAt(i++);
                while (i < code.length() && code.charAt(i) != quote) {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(quote).append(quote);
            } else {
                out.append(code.charAt(i++));
            }
        }
        return out.toString();
    }

    private static String newlinesIn(String code, int from, int to) {
        return "\n".repeat((int) code.substring(from, to).chars().filter(c -> c == '\n').count());
    }

    private static long lineOf(String code, int offset) {
        return code.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }
}
