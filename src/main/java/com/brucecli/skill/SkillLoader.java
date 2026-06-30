package com.brucecli.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SkillLoader {
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9._-]+");

    private final Path userSkillsDirectory;
    private final Path projectSkillsDirectory;

    public SkillLoader(Path userHome, Path workspaceRoot) {
        this.userSkillsDirectory = userHome.toAbsolutePath().normalize().resolve(".bruce/skills");
        this.projectSkillsDirectory = workspaceRoot.toAbsolutePath().normalize().resolve(".bruce/skills");
    }

    public SkillLoadResult load() {
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        List<String> overrides = new ArrayList<>();
        loadDirectory(userSkillsDirectory, SkillSource.USER, skills, diagnostics, overrides);
        loadDirectory(projectSkillsDirectory, SkillSource.PROJECT, skills, diagnostics, overrides);
        List<SkillDefinition> sorted = skills.values().stream()
            .sorted(Comparator.comparing(SkillDefinition::name))
            .toList();
        return new SkillLoadResult(sorted, diagnostics, overrides);
    }

    public Path userSkillsDirectory() {
        return userSkillsDirectory;
    }

    public Path projectSkillsDirectory() {
        return projectSkillsDirectory;
    }

    private void loadDirectory(
        Path directory,
        SkillSource source,
        Map<String, SkillDefinition> skills,
        List<String> diagnostics,
        List<String> overrides
    ) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> childDirectories;
        try (Stream<Path> stream = Files.list(directory)) {
            childDirectories = stream
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            diagnostics.add(directory + ": 无法扫描目录: " + e.getMessage());
            return;
        }

        for (Path skillDirectory : childDirectories) {
            Path skillFile = skillDirectory.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                diagnostics.add(skillDirectory + ": 缺少 SKILL.md");
                continue;
            }
            try {
                SkillDefinition definition = parse(skillFile, source);
                SkillDefinition previous = skills.put(definition.name(), definition);
                if (previous != null) {
                    overrides.add(definition.name() + ": " + source + " 覆盖 " + previous.source());
                }
            } catch (Exception e) {
                diagnostics.add(skillFile + ": " + e.getMessage());
            }
        }
    }

    private SkillDefinition parse(Path skillFile, SkillSource source) throws IOException {
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
            throw new IllegalArgumentException("必须以 YAML frontmatter 开头");
        }

        int closing = -1;
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("---".equals(line.trim())) {
                closing = i;
                break;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                metadata.put(
                    line.substring(0, separator).trim(),
                    unquote(line.substring(separator + 1).trim())
                );
            }
        }
        if (closing < 0) {
            throw new IllegalArgumentException("YAML frontmatter 缺少结束标记 ---");
        }

        String name = metadata.getOrDefault("name", "").trim();
        String description = metadata.getOrDefault("description", "").trim();
        String instructions = String.join("\n", lines.subList(closing + 1, lines.size())).strip();
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name 必须匹配 [a-z0-9._-]+");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("Skill 指令正文不能为空");
        }
        return new SkillDefinition(
            name,
            description,
            instructions,
            skillFile.getParent(),
            skillFile,
            source
        );
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
