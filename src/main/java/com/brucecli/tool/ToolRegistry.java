package com.brucecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brucecli.llm.ToolDefinition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 工具注册表：把 Java 代码里的能力暴露成模型可以调用的 tools。
 *
 * <p>模型看不到 Java 方法本身，它只能看到工具名、描述和参数 JSON Schema。
 * 当模型返回 tool_calls 时，Agent 再通过这个注册表找到对应的 ToolExecutor 执行。</p>
 */
public class ToolRegistry {
    // 命令输出最多返回这么多字符，避免一次工具结果撑爆后续模型上下文。
    private static final int OUTPUT_LIMIT = 12_000;

    // 学习项目里给 shell 命令设置一个硬超时，避免命令卡住整个 Agent。
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    // LinkedHashMap 可以保持工具注册顺序，便于调试时观察工具列表。
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    // 所有文件和命令操作都限制在这个目录下，降低误操作风险。
    private final Path workspaceRoot;

    public ToolRegistry(Path workspaceRoot) {
        this(workspaceRoot, true);
    }

    private ToolRegistry(Path workspaceRoot, boolean registerBuiltIns) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();

        if (registerBuiltIns) {
            // 按类别注册工具：文件、命令、代码项目创建。
            registerFileTools();
            registerShellTools();
        }
    }

    public static ToolRegistry empty(Path workspaceRoot) {
        return new ToolRegistry(workspaceRoot, false);
    }

    /**
     * 返回给模型看的工具定义，不包含真正的 Java 执行器。
     */
    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream()
            .map(Tool::definition)
            .toList();
    }

    public List<String> getToolNames() {
        return List.copyOf(tools.keySet());
    }

    public String buildToolPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Available tools:\n");
        List<String> visibleTools = tools.values().stream()
            .filter(tool -> tool.promptSnippet() != null && !tool.promptSnippet().isBlank())
            .map(tool -> "- " + tool.name() + ": " + tool.promptSnippet())
            .toList();
        if (visibleTools.isEmpty()) {
            prompt.append("(none)\n");
        } else {
            prompt.append(String.join("\n", visibleTools)).append('\n');
        }

        Set<String> guidelines = new LinkedHashSet<>();
        addDefaultGuidelines(guidelines);
        tools.values().stream()
            .flatMap(tool -> tool.promptGuidelines().stream())
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .forEach(guidelines::add);

        if (!guidelines.isEmpty()) {
            prompt.append("\nGuidelines:\n");
            for (String guideline : guidelines) {
                prompt.append("- ").append(guideline).append('\n');
            }
        }
        return prompt.toString().strip();
    }

    private void addDefaultGuidelines(Set<String> guidelines) {
        boolean hasExecuteCommand = tools.containsKey("execute_command");
        boolean hasReadFile = tools.containsKey("read_file");
        boolean hasEditFile = tools.containsKey("edit_file");
        boolean hasWriteFile = tools.containsKey("write_file");
        boolean hasFilesystemMcp = tools.keySet().stream()
            .anyMatch(name -> name.startsWith("mcp__filesystem__"));

        if (hasExecuteCommand) {
            guidelines.add("本地目录浏览、文件发现和全文搜索优先使用 execute_command 运行 ls、rg --files、rg <pattern> 或 find。");
            guidelines.add("构建、测试、Git 操作和脚本执行使用 execute_command。");
        }
        if (hasReadFile) {
            guidelines.add("读取已知路径的单个文件用 read_file；大文件按返回提示继续使用 offset/limit 读取。");
        }
        if (hasEditFile) {
            guidelines.add("小范围修改已有文件用 edit_file，old_text 必须精确且唯一匹配。");
        }
        if (hasWriteFile) {
            guidelines.add("新建文件或完整覆盖文件用 write_file，不要用它做小范围修改。");
        }
        if (hasFilesystemMcp) {
            guidelines.add("mcp__* 只在用户明确要求 MCP、内置工具无法满足，或需要该 MCP server 特有能力时使用。");
        }
    }

    /**
     * 允许外部动态注册工具，方便后续学习“插件式工具扩展”。
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * 执行模型返回的工具调用。
     *
     * @param name 工具名，例如 write_file
     * @param argumentsJson 模型生成的 JSON 参数字符串
     */
    public String executeTool(String name, String argumentsJson) {
        try {
            return executeTool(name, parseArguments(argumentsJson));
        } catch (Exception e) {
            return "工具参数解析失败: " + e.getMessage();
        }
    }

    /**
     * 直接用 Map 执行工具，主要用于测试和手动调用。
     */
    public String executeTool(String name, Map<String, String> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name + "，可用工具: " + String.join(", ", tools.keySet());
        }

        try {
            return tool.executor().execute(args);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    private void registerFileTools() {
        // read_file: 让模型先观察已有文件内容，再决定如何修改。
        register(new Tool(
            "read_file",
            "读取文件内容，用于查看代码、配置文件等；支持 offset/limit 按 1-based 行号分段读取大文件",
            createParameters(
                new Param("path", "string", "文件路径", true),
                new Param("offset", "integer", "起始行号，1-based，可选", false),
                new Param("limit", "integer", "最多读取行数，可选", false)
            ),
            args -> {
                Path path = resolveInsideWorkspace(args.get("path"));
                return readFile(path, args);
            },
            "Read known file contents with optional offset/limit for large files",
            List.of("Use read_file to examine known file paths instead of cat or sed.")
        ));

        // write_file: 写文件时自动创建父目录，方便模型一次完成新文件创建。
        register(new Tool(
            "write_file",
            "新建文件或完整覆盖文件内容，如果父目录不存在会自动创建；小范围修改已有文件应使用 edit_file",
            createParameters(
                new Param("path", "string", "文件路径", true),
                new Param("content", "string", "文件内容", true)
            ),
            args -> {
                Path path = resolveInsideWorkspace(args.get("path"));
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, args.getOrDefault("content", ""), StandardCharsets.UTF_8);
                return "文件已写入: " + workspaceRoot.relativize(path);
            },
            "Create new files or completely overwrite existing files",
            List.of("Use write_file only for new files or complete rewrites.")
        ));

        register(new Tool(
            "edit_file",
            "精确修改已有文件中的一段文本；old_text 必须在原文件中唯一匹配，适合小范围修改",
            createParameters(
                new Param("path", "string", "文件路径", true),
                new Param("old_text", "string", "要替换的原文，必须精确且唯一匹配", true),
                new Param("new_text", "string", "替换后的文本", true)
            ),
            this::editFile,
            "Make precise small edits by replacing one unique exact text block",
            List.of(
                "Use edit_file for precise changes to existing files.",
                "The old_text argument must match exactly and uniquely; if it appears multiple times, read the file and choose a more specific block."
            )
        ));

    }

    private void registerShellTools() {
        register(new Tool(
            "execute_command",
            "在工作目录内执行 Shell 命令，用于 ls、rg、find、git、build、test、脚本运行等通用本地操作",
            createParameters(new Param("command", "string", "要执行的命令", true)),
            args -> executeCommand(args.get("command")),
            "Execute shell commands for ls, rg, find, git, build, test, and scripts",
            List.of("Use execute_command for local exploration commands such as rg --files, rg <pattern>, find, and ls.")
        ));
    }


    private String readFile(Path path, Map<String, String> args) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        Integer offset = parsePositiveInt(args.get("offset"), "offset");
        Integer limit = parsePositiveInt(args.get("limit"), "limit");

        if (offset == null && limit == null && content.length() <= OUTPUT_LIMIT) {
            return "文件内容:\n" + content;
        }

        String[] lines = content.isEmpty() ? new String[0] : content.split("\\R", -1);
        int totalLines = lines.length;
        int startLine = offset == null ? 1 : offset;
        if (startLine < 1) {
            return "offset 必须大于等于 1";
        }
        if (limit != null && limit < 1) {
            return "limit 必须大于等于 1";
        }
        if (totalLines == 0) {
            if (startLine > 1) {
                return "offset 超出文件末尾: offset=" + startLine + ", 文件总行数=0";
            }
            return "文件内容 (lines 0-0 of 0):\n";
        }
        if (startLine > totalLines) {
            return "offset 超出文件末尾: offset=" + startLine + ", 文件总行数=" + totalLines;
        }

        int startIndex = startLine - 1;
        int requestedEnd = limit == null
            ? totalLines
            : Math.min(totalLines, startIndex + limit);
        StringBuilder selected = new StringBuilder();
        int endIndex = startIndex;
        boolean truncatedByChars = false;
        boolean partialLine = false;
        for (int i = startIndex; i < requestedEnd; i++) {
            String next = (selected.length() == 0 ? "" : "\n") + lines[i];
            if (selected.length() + next.length() > OUTPUT_LIMIT) {
                if (selected.length() == 0) {
                    selected.append(next, 0, Math.min(next.length(), OUTPUT_LIMIT));
                    endIndex = i + 1;
                    partialLine = true;
                }
                truncatedByChars = true;
                break;
            }
            selected.append(next);
            endIndex = i + 1;
        }

        int displayEndLine = Math.max(startLine, endIndex);
        StringBuilder result = new StringBuilder();
        result.append("文件内容 (lines ")
            .append(startLine)
            .append("-")
            .append(displayEndLine)
            .append(" of ")
            .append(totalLines)
            .append("):\n")
            .append(selected);

        if (partialLine) {
            result.append("\n\n[Line ")
                .append(displayEndLine)
                .append(" exceeds ")
                .append(OUTPUT_LIMIT)
                .append(" char limit. Use execute_command with sed/head/tail to inspect this long line.]");
        }

        boolean hasMore = endIndex < totalLines;
        if (hasMore) {
            int nextOffset = endIndex + 1;
            result.append("\n\n[Showing lines ")
                .append(startLine)
                .append("-")
                .append(displayEndLine)
                .append(" of ")
                .append(totalLines);
            if (truncatedByChars) {
                result.append(" (").append(OUTPUT_LIMIT).append(" char limit)");
            }
            result.append(". Use offset=")
                .append(nextOffset)
                .append(" to continue.]");
        }
        return result.toString();
    }

    private String editFile(Map<String, String> args) throws Exception {
        Path path = resolveInsideWorkspace(args.get("path"));
        String oldText = args.get("old_text");
        String newText = args.getOrDefault("new_text", "");
        if (oldText == null || oldText.isEmpty()) {
            return "edit_file 失败: old_text 不能为空，文件未修改";
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        int matches = countOccurrences(content, oldText);
        if (matches == 0) {
            return "edit_file 失败: old_text 未在文件中找到，文件未修改: " + workspaceRoot.relativize(path);
        }
        if (matches > 1) {
            return "edit_file 失败: old_text 匹配多处 (" + matches + ")，请提供更精确的 old_text，文件未修改: "
                + workspaceRoot.relativize(path);
        }

        String updated = content.replace(oldText, newText);
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return "文件已编辑: " + workspaceRoot.relativize(path)
            + " (替换 1 处，" + oldText.length() + " -> " + newText.length() + " 字符)";
    }

    private int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private Integer parsePositiveInt(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是整数: " + raw);
        }
    }

    private String executeCommand(String command) throws Exception {
        if (command == null || command.isBlank()) {
            return "命令不能为空";
        }

        // bash -lc 让命令表现更接近用户在终端里输入的效果。
        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-lc", command);
        processBuilder.directory(workspaceRoot.toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        // 单独开线程读取输出，避免进程输出过多时阻塞 waitFor。
        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        Future<String> outputFuture = outputReader.submit(() -> readProcessOutput(process));

        try {
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                // 超时后强制结束进程，并尽量返回已经读取到的输出，方便定位卡在哪一步。
                process.destroyForcibly();
                return "命令执行超时，已终止:\n" + collectOutput(outputFuture);
            }

            return String.format(
                "命令执行完成 (exit code: %d)%n%s",
                process.exitValue(),
                collectOutput(outputFuture)
            );
        } finally {
            outputReader.shutdownNow();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 超过上限后继续读但不继续追加，保证子进程不会因为管道满而卡住。
                if (output.length() < OUTPUT_LIMIT) {
                    output.append(line).append('\n');
                }
            }
        }
        return truncate(output.toString());
    }

    private String collectOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "输出读取超时";
        }
    }

    private String createProject(Map<String, String> args) throws Exception {
        String name = args.get("name");
        String type = args.getOrDefault("type", "java");
        if (name == null || name.isBlank()) {
            return "项目名称不能为空";
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            return "项目名称只能包含字母、数字、下划线、点和横线";
        }
        if (!"java".equalsIgnoreCase(type) && !"maven".equalsIgnoreCase(type)) {
            return "暂不支持项目类型: " + type;
        }

        Path projectDir = resolveInsideWorkspace(name);
        if (Files.exists(projectDir) && isNotEmpty(projectDir)) {
            return "项目已存在且不为空: " + name;
        }

        // 创建一个最小 Maven Java 项目，方便文章里的示例任务直接跑通。
        Path packageDir = projectDir.resolve("src/main/java/com/example");
        Path testDir = projectDir.resolve("src/test/java/com/example");
        Files.createDirectories(packageDir);
        Files.createDirectories(testDir);

        Files.writeString(projectDir.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>%s</artifactId>
                <version>1.0-SNAPSHOT</version>
                <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>
            </project>
            """.formatted(name), StandardCharsets.UTF_8);

        Files.writeString(packageDir.resolve("Hello.java"), """
            package com.example;

            public class Hello {
                public static void main(String[] args) {
                    System.out.println("Hello BruceCLI");
                }
            }
            """, StandardCharsets.UTF_8);

        return "项目已创建: " + name + " (类型: java)";
    }

    private JsonNode createParameters(Param... params) {
        // 生成 OpenAI-compatible tools 所需的 parameters JSON Schema。
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    private Map<String, String> parseArguments(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }

        // 模型返回的 arguments 是 JSON 字符串，这里转成工具执行器更容易使用的 Map。
        JsonNode root = mapper.readTree(argumentsJson);
        Map<String, String> args = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(), value.isTextual() ? value.asText() : value.toString());
        });
        return args;
    }

    private Path resolveInsideWorkspace(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path candidate = Path.of(rawPath);
        Path normalized = candidate.isAbsolute()
            ? candidate.normalize()
            : workspaceRoot.resolve(candidate).normalize();

        // 防止模型通过 ../ 或绝对路径读写工作目录外的文件。
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径超出工作目录: " + rawPath);
        }
        return normalized;
    }

    private boolean isNotEmpty(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        }
    }

    private String truncate(String output) {
        if (output.length() <= OUTPUT_LIMIT) {
            return output;
        }
        return output.substring(0, OUTPUT_LIMIT) + "\n... 输出过长，已截断 ...";
    }
}
