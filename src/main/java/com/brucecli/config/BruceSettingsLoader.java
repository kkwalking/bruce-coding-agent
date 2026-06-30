package com.brucecli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BruceSettingsLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    public BruceSettingsLoader(Path settingsFile) {
        this.settingsFile = settingsFile == null ? defaultSettingsFile() : settingsFile.toAbsolutePath().normalize();
    }

    public static BruceSettingsLoader defaults() {
        return new BruceSettingsLoader(defaultSettingsFile());
    }

    public static Path defaultSettingsFile() {
        return Path.of(System.getProperty("user.home"), ".bruce", "setting.json")
            .toAbsolutePath()
            .normalize();
    }

    public static Path resolveUserPath(String value) {
        String path = value == null || value.isBlank() ? "." : value.trim();
        if ("~".equals(path)) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (path.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), path.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public BruceSettings load() throws IOException {
        if (!Files.isRegularFile(settingsFile)) {
            return new BruceSettings();
        }
        BruceSettings settings = MAPPER.readValue(settingsFile.toFile(), BruceSettings.class);
        return settings == null ? new BruceSettings() : settings;
    }

    public void save(BruceSettings settings) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        MAPPER.writeValue(settingsFile.toFile(), settings == null ? new BruceSettings() : settings);
    }
}
