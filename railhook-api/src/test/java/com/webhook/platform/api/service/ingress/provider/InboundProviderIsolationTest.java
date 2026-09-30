package com.webhook.platform.api.service.ingress.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

// A branch on one provider's id in the core is how adding a provider used to take ten files.
@Tag("ratchet")
class InboundProviderIsolationTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java");
    private static final Path PROVIDER_PACKAGE =
            SOURCE_ROOT.resolve(InboundProvider.class.getPackageName().replace('.', '/'));

    @Test
    @DisplayName("no code outside the provider package branches on a provider's id")
    void theCoreNeverBranchesOnAProvider() throws IOException {
        String ids = TestInboundProviders.registry().all().stream()
                .map(InboundProvider::id)
                .collect(Collectors.joining("|"));
        Pattern branch = Pattern.compile(
                "\"(" + ids + ")\"\\s*\\.\\s*equals"
                        + "|equals(IgnoreCase)?\\s*\\(\\s*\"(" + ids + ")\""
                        + "|case\\s+\"(" + ids + ")\"");

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.startsWith(PROVIDER_PACKAGE))
                    .filter(f -> branch.matcher(read(f)).find())
                    .forEach(f -> offenders.add(SOURCE_ROOT.relativize(f).toString()));
        }

        assertEquals(Set.of(), offenders,
                "These files branch on a provider id. Move the behaviour into the provider's "
                        + "InboundProvider class (verify, eventId, handshake), or add a default method "
                        + "to InboundProvider if every provider needs the hook.");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
