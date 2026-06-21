package com.brucecli.integrated.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public class EnvLoader {
    public String get(String key) {
        for (Path envFile : candidateFiles()) {
            if (!Files.isRegularFile(envFile)) {
                continue;
            }
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

    private Set<Path> candidateFiles() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Set<Path> paths = new LinkedHashSet<>();
        paths.add(cwd.resolve(".env"));
        if (cwd.getParent() != null) {
            paths.add(cwd.getParent().resolve(".env"));
        }
        return paths;
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
