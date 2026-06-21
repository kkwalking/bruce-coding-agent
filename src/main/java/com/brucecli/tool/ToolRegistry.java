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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();

        // 按类别注册工具：文件、命令、代码项目创建。
        registerFileTools();
        registerShellTools();
        registerCodeTools();
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
            "读取文件内容，用于查看代码、配置文件等",
            createParameters(new Param("path", "string", "文件路径", true)),
            args -> {
                Path path = resolveInsideWorkspace(args.get("path"));
                return "文件内容:\n" + Files.readString(path, StandardCharsets.UTF_8);
            }
        ));

        // write_file: 写文件时自动创建父目录，方便模型一次完成新文件创建。
        register(new Tool(
            "write_file",
            "写入文件内容，如果父目录不存在会自动创建",
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
            }
        ));

        // list_dir: 给模型提供目录观察能力，避免它凭空猜测项目结构。
        register(new Tool(
            "list_dir",
            "列出目录内容",
            createParameters(new Param("path", "string", "目录路径", false)),
            args -> {
                Path dir = resolveInsideWorkspace(args.getOrDefault("path", "."));
                if (!Files.isDirectory(dir)) {
                    return "不是目录: " + workspaceRoot.relativize(dir);
                }
                try (Stream<Path> stream = Files.list(dir)) {
                    List<String> lines = stream
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(path -> (Files.isDirectory(path) ? "[D] " : "[F] ") + path.getFileName())
                        .toList();
                    return lines.isEmpty() ? "目录为空" : String.join("\n", lines);
                }
            }
        ));
    }

    private void registerShellTools() {
        // execute_command: 用于编译、运行测试等反馈闭环，是编程 Agent 的关键工具。
        register(new Tool(
            "execute_command",
            "在工作目录内执行 Shell 命令，用于编译、运行、Git 操作等",
            createParameters(new Param("command", "string", "要执行的命令", true)),
            args -> executeCommand(args.get("command"))
        ));
    }

    private void registerCodeTools() {
        // create_project: 示例工具，用来演示“一个工具内部可以封装多步文件操作”。
        register(new Tool(
            "create_project",
            "创建一个基础 Java Maven 项目结构",
            createParameters(
                new Param("name", "string", "项目名称", true),
                new Param("type", "string", "项目类型，目前支持 java", false)
            ),
            this::createProject
        ));
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
