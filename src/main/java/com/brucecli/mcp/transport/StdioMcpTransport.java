package com.brucecli.mcp.transport;

import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.runtime.LogRingBuffer;
import com.brucecli.runtime.DaemonThreadFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StdioMcpTransport implements McpTransport {
    private final McpServerConfig config;
    private final Path workspaceRoot;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final LogRingBuffer logs = new LogRingBuffer(200);
    private final ExecutorService readerPool;

    private Process process;
    private BufferedWriter stdin;

    public StdioMcpTransport(McpServerConfig config, Path workspaceRoot) {
        this.config = Objects.requireNonNull(config);
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.readerPool = Executors.newFixedThreadPool(2, new DaemonThreadFactory("bruce-mcp-stdio-" + config.name()));
    }

    @Override
    public void start() throws Exception {
        if (config.command().isBlank()) {
            throw new IllegalArgumentException("stdio MCP server 缺少 command: " + config.name());
        }
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.args());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspaceRoot.toFile());
        processBuilder.environment().putAll(config.env());
        process = processBuilder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        readerPool.submit(this::readStdout);
        readerPool.submit(this::readStderr);
    }

    @Override
    public JsonNode request(JsonNode message, Duration timeout) throws Exception {
        ensureStarted();
        String id = message.path("id").asText();
        if (id.isBlank()) {
            throw new IllegalArgumentException("JSON-RPC 请求缺少 id");
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeMessage(message);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void notify(JsonNode message) throws Exception {
        ensureStarted();
        writeMessage(message);
    }

    @Override
    public List<String> logs() {
        return logs.lines();
    }

    @Override
    public Long pid() {
        return process == null ? null : process.pid();
    }

    @Override
    public void close() {
        try {
            if (stdin != null) {
                stdin.close();
            }
            if (process != null && process.isAlive() && !process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
            if (process != null) {
                process.destroyForcibly();
            }
        } finally {
            readerPool.shutdownNow();
        }
    }

    private void writeMessage(JsonNode message) throws Exception {
        synchronized (this) {
            stdin.write(mapper.writeValueAsString(message));
            stdin.newLine();
            stdin.flush();
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                dispatch(line);
            }
        } catch (Exception e) {
            logs.add("[stdout] " + e.getMessage());
        }
    }

    private void dispatch(String line) throws Exception {
        JsonNode message = mapper.readTree(line);
        JsonNode id = message.path("id");
        if (id.isMissingNode() || id.isNull()) {
            logs.add("[notification] " + line);
            return;
        }
        CompletableFuture<JsonNode> future = pending.get(id.asText());
        if (future != null) {
            future.complete(message);
        } else {
            logs.add("[unmatched] " + line);
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
        } catch (Exception e) {
            logs.add("[stderr] " + e.getMessage());
        }
    }

    private void ensureStarted() {
        if (process == null || stdin == null) {
            throw new IllegalStateException("stdio MCP transport 尚未启动: " + config.name());
        }
        if (!process.isAlive()) {
            throw new IllegalStateException("stdio MCP server 已退出: " + config.name());
        }
    }
}
