package com.brucecli.mcp.runtime;

import com.brucecli.mcp.config.McpConfig;
import com.brucecli.mcp.config.McpConfigLoader;
import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.protocol.McpClient;
import com.brucecli.mcp.protocol.McpSchemaSanitizer;
import com.brucecli.mcp.protocol.McpTool;
import com.brucecli.mcp.transport.McpTransport;
import com.brucecli.mcp.transport.McpTransportFactory;
import com.brucecli.runtime.DaemonThreadFactory;
import com.brucecli.tool.Tool;
import com.brucecli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class McpServerManager implements AutoCloseable {
    public static final String TOOL_PREFIX = "mcp__";
    private static final Duration DEFAULT_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final Path workspaceRoot;
    private final McpConfig config;
    private final McpTransportFactory transportFactory;
    private final Consumer<String> progressReporter;
    private final Duration progressInterval;
    private final McpSchemaSanitizer schemaSanitizer = new McpSchemaSanitizer();
    private final ConcurrentHashMap<String, McpServerRuntime> runtimes = new ConcurrentHashMap<>();
    private final ExecutorService startupPool;

    public McpServerManager(Path workspaceRoot) throws Exception {
        this(
            workspaceRoot,
            new McpConfigLoader(workspaceRoot).load(),
            new McpTransportFactory(),
            System.out,
            DEFAULT_PROGRESS_INTERVAL
        );
    }

    public McpServerManager(Path workspaceRoot, PrintStream progressOut) throws Exception {
        this(
            workspaceRoot,
            new McpConfigLoader(workspaceRoot).load(),
            new McpTransportFactory(),
            progressReporter(progressOut),
            DEFAULT_PROGRESS_INTERVAL
        );
    }

    public McpServerManager(Path workspaceRoot, Consumer<String> progressReporter) throws Exception {
        this(
            workspaceRoot,
            new McpConfigLoader(workspaceRoot).load(),
            new McpTransportFactory(),
            progressReporter,
            DEFAULT_PROGRESS_INTERVAL
        );
    }

    McpServerManager(Path workspaceRoot, McpConfig config, McpTransportFactory transportFactory) {
        this(workspaceRoot, config, transportFactory, progressReporter(System.out), DEFAULT_PROGRESS_INTERVAL);
    }

    McpServerManager(
        Path workspaceRoot,
        McpConfig config,
        McpTransportFactory transportFactory,
        PrintStream progressOut,
        Duration progressInterval
    ) {
        this(workspaceRoot, config, transportFactory, progressReporter(progressOut), progressInterval);
    }

    McpServerManager(
        Path workspaceRoot,
        McpConfig config,
        McpTransportFactory transportFactory,
        Consumer<String> progressReporter,
        Duration progressInterval
    ) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.config = config == null ? new McpConfig(List.of(), List.of()) : config;
        this.transportFactory = transportFactory;
        this.progressReporter = progressReporter == null ? ignored -> {
        } : progressReporter;
        if (progressInterval == null || progressInterval.isZero() || progressInterval.isNegative()) {
            throw new IllegalArgumentException("MCP 启动进度间隔必须大于 0");
        }
        this.progressInterval = progressInterval;
        int poolSize = Math.max(1, Math.min(this.config.servers().size(), 8));
        this.startupPool = Executors.newFixedThreadPool(poolSize, new DaemonThreadFactory("bruce-mcp-startup"));
        for (McpServerConfig server : this.config.servers()) {
            runtimes.put(server.name(), new McpServerRuntime(server));
        }
    }

    public void startAll() {
        if (!config.configured()) {
            return;
        }
        reportProgress("启动 MCP server (%d 个)...".formatted(config.servers().size()));
        long startedAtNanos = System.nanoTime();
        List<StartupTask> tasks = config.servers().stream()
            .map(server -> new StartupTask(
                server,
                CompletableFuture.runAsync(() -> startOne(server.name(), true), startupPool)
            ))
            .toList();
        ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory("bruce-mcp-progress")
        );
        long intervalNanos = progressInterval.toNanos();
        ScheduledFuture<?> progressTask = progressExecutor.scheduleAtFixedRate(
            () -> printStartupProgress(tasks, startedAtNanos),
            intervalNanos,
            intervalNanos,
            TimeUnit.NANOSECONDS
        );
        try {
            CompletableFuture.allOf(tasks.stream()
                .map(StartupTask::future)
                .toArray(CompletableFuture[]::new)).join();
        } finally {
            progressTask.cancel(false);
            progressExecutor.shutdownNow();
        }
    }

    public boolean configured() {
        return config.configured();
    }

    public List<Path> loadedFiles() {
        return config.loadedFiles();
    }

    public synchronized void registerTools(ToolRegistry registry) {
        for (McpServerRuntime runtime : sortedRuntimes()) {
            for (McpToolDescriptor descriptor : runtime.tools()) {
                registry.register(new Tool(
                    descriptor.registeredName(),
                    "[MCP:" + descriptor.serverName() + "] " + descriptor.description(),
                    descriptor.inputSchema(),
                    args -> callTool(descriptor.registeredName(), args)
                ));
            }
        }
    }

    public String callTool(String registeredName, Map<String, String> args) throws Exception {
        McpToolDescriptor descriptor = findTool(registeredName);
        if (descriptor == null) {
            return "未知 MCP 工具: " + registeredName;
        }
        McpServerRuntime runtime = runtimes.get(descriptor.serverName());
        if (runtime == null || runtime.client() == null) {
            return "MCP server 不可用: " + descriptor.serverName();
        }
        JsonNode arguments = runtime.client().mapToArguments(args);
        return runtime.client().callTool(descriptor.toolName(), arguments);
    }

    public synchronized void restart(String name) {
        requireServer(name);
        startOne(name, false);
    }

    public synchronized void disable(String name) {
        McpServerRuntime runtime = requireServer(name);
        runtime.disabled("已通过 CLI 禁用");
    }

    public synchronized void enable(String name) {
        requireServer(name);
        startOne(name, false);
    }

    public String logs(String name) {
        return requireServer(name).logs();
    }

    public List<McpServerStatus> statuses() {
        return sortedRuntimes().stream()
            .map(McpServerRuntime::status)
            .toList();
    }

    public List<String> serverNames() {
        return sortedRuntimes().stream()
            .map(runtime -> runtime.config().name())
            .toList();
    }

    public String statusTable() {
        if (!configured()) {
            return """
                MCP server：未配置。
                配置文件位置：
                - ~/.brucecli/mcp.json
                - .brucecli/mcp.json
                """.trim();
        }
        StringBuilder builder = new StringBuilder("MCP server 状态:\n");
        for (McpServerStatus status : statuses()) {
            builder.append("- ").append(status.toDisplayLine()).append('\n');
        }
        if (!loadedFiles().isEmpty()) {
            builder.append("配置来源: ");
            builder.append(loadedFiles().stream().map(Path::toString).toList());
        }
        return builder.toString().trim();
    }

    public String summary() {
        if (!configured()) {
            return "未配置";
        }
        long ready = statuses().stream().filter(status -> status.state() == McpServerState.READY).count();
        return "已配置 " + statuses().size() + " 个，ready " + ready + " 个";
    }

    @Override
    public synchronized void close() {
        runtimes.values().forEach(McpServerRuntime::close);
        startupPool.shutdownNow();
    }

    private void startOne(String name, boolean honorConfiguredDisabled) {
        McpServerRuntime runtime = requireServer(name);
        McpServerConfig server = runtime.config();
        if (honorConfiguredDisabled && server.disabled()) {
            runtime.disabled("配置已禁用");
            return;
        }
        try {
            McpTransport transport = transportFactory.create(server, workspaceRoot);
            McpClient client = new McpClient(server.name(), transport);
            client.start();
            List<McpToolDescriptor> descriptors = new ArrayList<>();
            for (McpTool tool : client.listTools()) {
                if (tool.name() == null || tool.name().isBlank()) {
                    continue;
                }
                descriptors.add(new McpToolDescriptor(
                    server.name(),
                    tool.name(),
                    registeredName(server.name(), tool.name()),
                    tool.description() == null || tool.description().isBlank() ? "MCP 工具 " + tool.name() : tool.description(),
                    schemaSanitizer.sanitize(tool.inputSchema())
                ));
            }
            runtime.ready(client, descriptors);
        } catch (Exception e) {
            runtime.error(e.getMessage());
        }
    }

    private void printStartupProgress(List<StartupTask> tasks, long startedAtNanos) {
        long waitedSeconds = Duration.ofNanos(System.nanoTime() - startedAtNanos).toSeconds();
        for (StartupTask task : tasks) {
            if (!task.future().isDone()) {
                reportProgress(
                    "%s %s 启动中... (已等待 %ds)".formatted(
                        task.server().name(),
                        task.server().type().name().toLowerCase(),
                        waitedSeconds
                    )
                );
            }
        }
    }

    private void reportProgress(String message) {
        progressReporter.accept(message);
    }

    private static Consumer<String> progressReporter(PrintStream progressOut) {
        PrintStream out = progressOut == null ? System.out : progressOut;
        return out::println;
    }

    private List<McpServerRuntime> sortedRuntimes() {
        return runtimes.values().stream()
            .sorted(Comparator.comparing(runtime -> runtime.config().name()))
            .toList();
    }

    private McpServerRuntime requireServer(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server 名称不能为空");
        }
        McpServerRuntime runtime = runtimes.get(name);
        if (runtime == null) {
            throw new IllegalArgumentException("未知 MCP server: " + name);
        }
        return runtime;
    }

    private String registeredName(String serverName, String toolName) {
        return TOOL_PREFIX + sanitizeName(serverName) + "__" + sanitizeName(toolName);
    }

    private McpToolDescriptor findTool(String registeredName) {
        for (McpServerRuntime runtime : runtimes.values()) {
            for (McpToolDescriptor descriptor : runtime.tools()) {
                if (descriptor.registeredName().equals(registeredName)) {
                    return descriptor;
                }
            }
        }
        return null;
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private record StartupTask(McpServerConfig server, CompletableFuture<Void> future) {
    }
}
