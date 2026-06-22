package com.brucecli.integrated.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class EnvLoader {
    public String get(String key) {
        Path envFile = envFile();
        if (Files.isRegularFile(envFile)) {
            try {
                String value = readValue(envFile, key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            } catch (IOException e) {
                System.err.println("读取 .env 失败: " + e.getMessage());
            }
        }
        return System.getenv(key);
    }

    private Path envFile() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return cwd.resolve(".env");
    }

    private String readValue(Path file, String key) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            if (value.startsWith("export ")) {
                value = value.substring("export ".length()).trim();
            }
            if (value.startsWith(key + "=") || value.matches(key + "\\s*=.*")) {
                return unquote(value.substring(value.indexOf('=') + 1).trim());
            }
        }
        return null;
    }

    private String unquote(String value) {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
