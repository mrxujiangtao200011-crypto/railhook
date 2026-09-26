package com.webhook.platform.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnDefaultConfigWhenFileDoesNotExist() {
        CliConfig config = new CliConfigService(tempDir.resolve("nonexistent/config.json")).load();

        assertEquals("http://localhost:8080", config.getBackendUrl());
        assertFalse(config.isAuthenticated());
    }

    @Test
    void shouldHandleCorruptedConfigGracefully() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "NOT VALID JSON {{{");

        CliConfig config = new CliConfigService(configPath).load();

        assertEquals("http://localhost:8080", config.getBackendUrl());
    }

    @Test
    void shouldIgnoreUnknownFieldsInConfig() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath,
                "{\"backendUrl\":\"https://test.com\",\"unknownField\":\"value\",\"accessToken\":\"tok\"}");

        CliConfig config = new CliConfigService(configPath).load();

        assertEquals("https://test.com", config.getBackendUrl());
        assertEquals("tok", config.getAccessToken());
    }

    @Test
    void shouldGenerateCorrectWsUrl() {
        CliConfig config = new CliConfig();

        config.setBackendUrl("http://localhost:8080");
        assertEquals("ws://localhost:8080", config.getWsUrl());

        config.setBackendUrl("https://api.example.com");
        assertEquals("wss://api.example.com", config.getWsUrl());
    }
}
