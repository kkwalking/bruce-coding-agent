package com.brucecli.skill;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SkillManager {
    private static final int MAX_CATALOG_CHARS = 8_000;
    private static final int MAX_ACTIVE_SKILLS = 3;
    private static final int MAX_INSTRUCTION_CHARS = 24_000;
    private static final int MAX_RESOURCE_CHARS = 12_000;

    private final Path userHome;
    private SkillLoader loader;
    private volatile SkillLoadResult snapshot;
    private final Map<String, String> activeInstructions = new LinkedHashMap<>();
    private int activeInstructionChars;

    public SkillManager(Path workspaceRoot) {
        this(Path.of(System.getProperty("user.home")), workspaceRoot);
    }

    public SkillManager(Path userHome, Path workspaceRoot) {
        this.userHome = userHome.toAbsolutePath().normalize();
        this.loader = new SkillLoader(userHome, workspaceRoot);
        this.snapshot = loader.load();
    }

    public synchronized SkillLoadResult reload() {
        SkillLoadResult loaded = loader.load();
        snapshot = loaded;
        return loaded;
    }

    public synchronized SkillLoadResult changeWorkspace(Path workspaceRoot) {
        loader = new SkillLoader(userHome, workspaceRoot);
        return reload();
    }

    public List<SkillDefinition> skills() {
        return snapshot.skills();
    }

    public List<String> diagnostics() {
        return snapshot.diagnostics();
    }

    public List<String> overrides() {
        return snapshot.overrides();
    }

    public Optional<SkillDefinition> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return snapshot.skills().stream()
            .filter(skill -> skill.name().equals(name.trim()))
            .findFirst();
    }

    public String catalogPrompt() {
        List<SkillDefinition> available = snapshot.skills();
        if (available.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("""
            可用 Skills（当前仅提供名称和描述）：
            当用户任务与某个 Skill 描述匹配时，先调用 load_skill(name) 加载完整指令，再继续任务。
            不要凭名称猜测 Skill 内容；未调用 load_skill 的 Skill 不算已激活。
            """);
        for (SkillDefinition skill : available) {
            String line = "\n- " + skill.name() + ": " + skill.description();
            if (builder.length() + line.length() > MAX_CATALOG_CHARS) {
                int remaining = MAX_CATALOG_CHARS - builder.length();
                if (remaining > 0) {
                    builder.append(line, 0, Math.min(remaining, line.length()));
                }
                break;
            }
            builder.append(line);
        }
        return builder.toString();
    }

    public synchronized void beginTask() {
        activeInstructions.clear();
        activeInstructionChars = 0;
    }

    public synchronized String loadSkill(String name) {
        SkillDefinition skill = find(name)
            .orElseThrow(() -> new IllegalArgumentException("未知 Skill: " + name));
        String existing = activeInstructions.get(skill.name());
        if (existing != null) {
            return existing;
        }
        if (activeInstructions.size() >= MAX_ACTIVE_SKILLS) {
            throw new IllegalStateException("当前任务最多激活 3 个 Skill");
        }

        String section = renderSkill(skill);
        int remaining = MAX_INSTRUCTION_CHARS - activeInstructionChars;
        if (remaining <= 0) {
            throw new IllegalStateException("当前任务 Skill 指令已达到 24000 字符上限");
        }
        if (section.length() > remaining) {
            section = section.substring(0, remaining);
        }
        activeInstructions.put(skill.name(), section);
        activeInstructionChars += section.length();
        return section;
    }

    public synchronized String activeInstructions() {
        return String.join("\n\n", activeInstructions.values());
    }

    public synchronized void endTask() {
        activeInstructions.clear();
        activeInstructionChars = 0;
    }

    public synchronized boolean isActive(String skillName) {
        return activeInstructions.containsKey(skillName);
    }

    public String readResource(String skillName, String rawPath) throws Exception {
        SkillDefinition skill = find(skillName)
            .orElseThrow(() -> new IllegalArgumentException("未知 Skill: " + skillName));
        if (!isActive(skill.name())) {
            throw new IllegalArgumentException("Skill 未在当前任务中激活: " + skill.name());
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("资源路径不能为空");
        }
        Path relative = Path.of(rawPath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("资源路径必须是 Skill 内的相对路径");
        }

        Path rootReal = skill.rootDirectory().toRealPath();
        Path normalized = skill.rootDirectory().resolve(relative).normalize();
        if (!normalized.startsWith(skill.rootDirectory())) {
            throw new IllegalArgumentException("资源路径超出 Skill 目录: " + rawPath);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("资源不存在: " + rawPath);
        }
        Path targetReal = normalized.toRealPath();
        if (!targetReal.startsWith(rootReal)) {
            throw new IllegalArgumentException("资源路径通过符号链接逃逸 Skill 目录: " + rawPath);
        }
        if (!Files.isRegularFile(targetReal)) {
            throw new IllegalArgumentException("资源不是普通文件: " + rawPath);
        }
        String content = Files.readString(targetReal, StandardCharsets.UTF_8);
        if (content.length() <= MAX_RESOURCE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_RESOURCE_CHARS) + "\n... Skill 资源过长，已截断 ...";
    }

    public Path userSkillsDirectory() {
        return loader.userSkillsDirectory();
    }

    public Path projectSkillsDirectory() {
        return loader.projectSkillsDirectory();
    }

    private String renderSkill(SkillDefinition skill) {
        return """
            ## Skill: %s
            描述: %s

            %s
            """.formatted(skill.name(), skill.description(), skill.instructions()).strip();
    }
}
