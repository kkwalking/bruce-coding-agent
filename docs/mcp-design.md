# Bruce CLI MCP 设计说明

本文介绍 Bruce CLI 中 MCP（Model Context Protocol）能力的设计与每个类的职责。MCP 子系统位于 `src/main/java/com/brucecli/mcp`，目标是把第三方 MCP server 暴露的工具接入现有统一 CLI，而不是新建独立 demo 入口。

## 总体流程

MCP 集成链路如下：

1. `McpConfigLoader` 读取用户级和项目级 MCP 配置。
2. `McpServerManager` 为每个 server 创建运行时对象，并并行启动。
3. `McpTransportFactory` 按配置选择 stdio 或 Streamable HTTP 传输。
4. `McpClient` 完成 `initialize`、`notifications/initialized`、`tools/list`。
5. `McpSchemaSanitizer` 清洗工具参数 Schema。
6. `McpServerManager` 将 MCP 工具注册到现有 `ToolRegistry`，工具名格式为 `mcp__server__tool`。
7. Agent 调用 MCP 工具时，仍走现有 Tool Call、HITL 审批和输出截断流程。

## 配置层

### `McpTransportType`

枚举 MCP 传输类型：

- `STDIO`：本地子进程，通过 stdin/stdout 交换 JSON-RPC 消息。
- `HTTP`：远程 Streamable HTTP，通过 POST 发送请求，响应可为普通 JSON 或 SSE。

该枚举让上层不需要依赖配置里的原始字符串。

### `McpServerConfig`

单个 MCP server 的配置模型。

主要字段：

- `name`：server 名称，也是工具命名空间的一部分。
- `type`：传输类型，缺省为 `STDIO`。
- `command`、`args`、`env`：stdio server 的启动配置。
- `url`、`headers`：HTTP server 的请求配置。
- `disabled`：是否在启动时跳过。

构造方法会做基础归一化：空集合转为空不可变集合，空字符串去掉多余空白，缺省传输类型回落到 `STDIO`。

### `McpConfig`

完整 MCP 配置聚合对象。

它保存：

- `servers`：所有 server 配置。
- `loadedFiles`：实际加载到的配置文件路径。

`configured()` 用于判断当前是否存在 MCP 配置。如果没有配置，CLI 会展示“未配置”，但不会影响主程序启动。

### `McpConfigLoader`

MCP 配置加载器。

加载顺序：

1. `~/.brucecli/mcp.json`
2. `.brucecli/mcp.json`

同名 server 后加载的项目级配置会覆盖用户级配置。

它支持的配置格式是常见 `mcpServers` 写法：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"]
    }
  }
}
```

变量替换能力：

- `${PROJECT_DIR}`：当前 CLI 工作目录。
- `${HOME}`：当前用户 home 目录。
- `${VARNAME}`：优先读取系统环境变量，再读取当前项目 `.env`。

`detectType()` 会把 `type=http`、`type=streamable_http`、`type=streamable-http` 或存在 `url` 的配置识别为 HTTP，其余默认 stdio。

## 协议层

### `McpProtocol`

MCP 协议常量类。

当前只定义：

```java
public static final String VERSION = "2025-03-26";
```

HTTP 传输会把该版本写入 `MCP-Protocol-Version` 请求头；初始化握手也会使用同一版本。

### `McpException`

MCP 专用异常。

`McpClient` 在收到 JSON-RPC `error` 或响应缺少 `result` 时会抛出它。这样调用方可以区分 MCP 协议错误和普通 Java 运行时错误。

### `McpTool`

MCP server 通过 `tools/list` 返回的工具描述模型。

字段：

- `name`：MCP server 内部工具名。
- `description`：工具描述。
- `inputSchema`：原始 JSON Schema。

它是协议层的原始工具结构，后续会转换成运行时层的 `McpToolDescriptor`。

### `McpClient`

MCP JSON-RPC 客户端。

核心职责：

- 启动传输层。
- 发送 `initialize` 握手。
- 发送 `notifications/initialized` 通知。
- 调用 `tools/list` 获取工具。
- 调用 `tools/call` 执行工具。
- 将 MCP `content` 数组整理成可返回给 LLM 的文本。

关键超时：

- initialize：30 秒。
- tools/list 和 tools/call：60 秒。

`request()` 会生成 JSON-RPC 请求：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}
```

`formatToolResult()` 只拼接 `type=text` 的内容；非文本内容会返回占位说明，避免图片、二进制等内容直接污染模型上下文。

`mapToArguments()` 负责把 `ToolRegistry` 的 `Map<String, String>` 参数转回 JSON。它会尽量识别对象、数组、布尔值、数字和 `null`，解析失败时保留字符串。

### `McpSchemaSanitizer`

MCP 工具参数 Schema 清洗器。

清洗原因：很多 MCP server 返回的 JSON Schema 包含 `$ref`、`anyOf`、`oneOf`、`$defs` 等高级特性，LLM 生成 Function Calling 参数时不一定能稳定处理。

处理策略：

- 删除 `$schema`、`$id`、`$defs`、`definitions`、`$ref`。
- 将 `anyOf`、`oneOf` 转成 `description` 文本提示。
- 缺失 `type` 时补成 `object`。
- 缺失 `properties` 时补空对象。
- 缺失 `required` 时补空数组。
- 非 object Schema 会包一层 `object`，字段名为 `value`。
- 过长描述截断到 1000 字符。

该类保证 MCP 工具注册到 LLM tools 时，参数 Schema 尽量符合当前项目的 Tool Call 使用习惯。

## 传输层

### `McpTransport`

传输层统一接口。

方法：

- `start()`：启动传输。
- `request(JsonNode, Duration)`：发送有响应的 JSON-RPC 请求。
- `notify(JsonNode)`：发送无响应通知。
- `logs()`：读取最近日志。
- `pid()`：返回进程 PID，只有 stdio 通常有值。
- `close()`：关闭传输资源。

协议层的 `McpClient` 只依赖该接口，因此不关心底层是本地子进程还是远程 HTTP。

### `StdioMcpTransport`

stdio MCP 传输实现。

核心机制：

- 用 `ProcessBuilder` 启动配置里的 `command + args`。
- 工作目录设为 `workspaceRoot`。
- 将配置里的 `env` 注入子进程环境变量。
- 通过 stdin 逐行写入 JSON-RPC。
- 通过 stdout 逐行读取 NDJSON 响应。
- stderr 写入 `LogRingBuffer`，供 `/mcp logs <name>` 查看。

请求响应配对：

- 每个 JSON-RPC 请求必须有 `id`。
- `pending` 使用 `ConcurrentHashMap<String, CompletableFuture<JsonNode>>` 保存等待中的请求。
- stdout 读取线程解析响应后按 `id` 找到对应 future 并完成它。

关闭策略：

1. 关闭 stdin，给 server 主动退出机会。
2. 等 1 秒仍未退出，则调用 `process.destroy()`。
3. 再等 2 秒仍未退出，则调用 `process.destroyForcibly()`。

这个策略避免 stdio MCP server 残留成僵尸进程。

### `StreamableHttpMcpTransport`

Streamable HTTP MCP 传输实现。

核心机制：

- 使用 OkHttp 向配置的 `url` 发送 POST。
- 请求头包含：
  - `Content-Type: application/json`
  - `Accept: application/json, text/event-stream`
  - `MCP-Protocol-Version: 2025-03-26`
- 合并配置里的自定义 headers。
- 如果服务端返回 `Mcp-Session-Id`，后续请求自动带上。

响应解析：

- `Content-Type` 包含 `text/event-stream` 时按 SSE 解析。
- 否则按普通 JSON 解析。

SSE 解析逻辑会收集 `data:` 行，遇到空行时组成一条完整 JSON 消息，并按请求 id 匹配目标响应。

关闭逻辑：

- 如果存在 session id，`close()` 会发送 DELETE 请求通知远端清理会话。

### `McpTransportFactory`

传输工厂。

根据 `McpServerConfig.type()` 创建：

- `HTTP` -> `StreamableHttpMcpTransport`
- 其他 -> `StdioMcpTransport`

它让 `McpServerManager` 不需要直接写传输选择逻辑。

## 运行时层

### `McpServerState`

server 生命周期状态枚举：

- `READY`：启动成功，工具已发现。
- `ERROR`：启动或运行失败。
- `DISABLED`：配置禁用或 CLI 禁用。

### `McpServerStatus`

用于 CLI 展示的 server 状态快照。

字段：

- `name`
- `state`
- `transportType`
- `toolCount`
- `uptime`
- `pid`
- `error`

`toDisplayLine()` 会格式化成一行状态文本，例如：

```text
filesystem | ready | stdio | 11 tools | 180s | PID 12345
```

### `McpToolDescriptor`

MCP 工具进入 Bruce CLI 后的运行时描述。

字段：

- `serverName`：来源 server。
- `toolName`：server 内原始工具名。
- `registeredName`：注册到 `ToolRegistry` 的名字。
- `description`：给 LLM 看的工具描述。
- `inputSchema`：清洗后的参数 Schema。

`registeredName` 使用命名空间格式：

```text
mcp__server__tool
```

这样可以避免 MCP 工具和内置工具重名，也方便 HITL 和审计策略识别第三方工具。

### `LogRingBuffer`

固定容量日志环形缓冲。

用途：

- 保存 stdio server 的 stderr。
- 保存 HTTP transport 的错误日志。
- 为 `/mcp logs <name>` 提供最近日志。

默认使用 200 行。超过容量时会丢弃最旧日志。

### `McpServerRuntime`

单个 MCP server 的运行时状态对象。

它保存：

- 原始 `McpServerConfig`。
- 当前 `McpClient`。
- 当前 `McpServerState`。
- 错误信息。
- 启动时间。
- 已发现的 `McpToolDescriptor` 列表。

主要方法：

- `ready()`：切换为 READY，保存 client 和工具列表。
- `error()`：切换为 ERROR，并关闭旧 client。
- `disabled()`：切换为 DISABLED，并关闭旧 client。
- `status()`：生成 `McpServerStatus`。
- `logs()`：读取 server 日志或错误文本。
- `close()`：关闭 client。

这个类只管理一个 server，不负责全局调度。

### `McpServerManager`

MCP 子系统的核心编排器。

核心职责：

- 加载配置。
- 为每个 server 建立 `McpServerRuntime`。
- 并行启动所有 server。
- 执行 initialize 和工具发现。
- 清洗 Schema。
- 把 MCP 工具注册到 `ToolRegistry`。
- 接收 ToolRegistry 的工具调用并转发给对应 MCP server。
- 提供 `/mcp` 命令需要的状态、日志、重启、启用和禁用能力。

启动策略：

- 使用固定线程池并行启动。
- 线程池大小为 `min(serverCount, 8)`。
- 使用 daemon 线程，避免阻塞 JVM 退出。
- 单个 server 启动失败只影响自己的状态，不影响其他 server。

工具注册：

```java
registry.register(new Tool(
    descriptor.registeredName(),
    "[MCP:" + descriptor.serverName() + "] " + descriptor.description(),
    descriptor.inputSchema(),
    args -> callTool(descriptor.registeredName(), args)
));
```

调用 MCP 工具时，manager 会根据 `registeredName` 找到对应 `McpToolDescriptor`，再通过该 server 的 `McpClient` 调用原始工具名。

CLI 管理方法：

- `statusTable()`：展示所有 server 状态。
- `summary()`：用于 `/status` 总览。
- `restart(name)`：重启某个 server。
- `disable(name)`：运行期禁用某个 server。
- `enable(name)`：运行期重新启动某个 server。
- `logs(name)`：查看最近日志。

## 与统一 CLI 的集成点

### `IntegratedRuntime`

统一 runtime 中新增了 `McpServerManager`。

启动时：

1. 根据 `workspaceRoot` 创建 manager。
2. 调用 `startAll()` 并行启动配置中的 server。
3. `rebuildComponents()` 时调用 `mcpManager.registerTools(toolRegistry)`。

关闭时：

- `close()` 会关闭 `mcpManager`，释放 stdio 子进程和 HTTP session。

对 CLI 暴露的方法：

- `mcpStatus()`
- `mcpLogs(name)`
- `restartMcpServer(name)`
- `disableMcpServer(name)`
- `enableMcpServer(name)`

对 Agent 的额外提示：

- 当 MCP 已配置时，system prompt 会说明 `mcp__server__tool` 的命名方式和第三方内容不可信的边界。

### `RuntimeStatus`

状态对象新增 `mcpSummary` 字段。

`/status` 会显示：

```text
MCP: 未配置
```

或：

```text
MCP: 已配置 2 个，ready 1 个
```

### `IntegratedCommandProcessor`

新增 MCP 管理命令：

```text
/mcp
/mcp restart <name>
/mcp logs <name>
/mcp disable <name>
/mcp enable <name>
```

这些命令全部转发到 `IntegratedRuntime`，仍然使用统一 CLI 入口 `IntegratedMain`。

### `ApprovalPolicy`

HITL 审批策略新增 MCP 工具识别：

```java
toolName != null && toolName.startsWith("mcp__")
```

所有 MCP 工具默认需要审批。原因是 MCP 工具来自第三方 server，可能访问本地文件、远程服务或执行外部操作，安全等级应高于普通只读内置工具。

## 测试类

### `McpConfigLoaderTest`

验证：

- 用户级配置和项目级配置都会加载。
- 项目级同名 server 会覆盖用户级。
- `${PROJECT_DIR}`、`${HOME}`、`${GLM_API_KEY}` 等变量可替换。
- `.env` 变量可以参与替换。
- 存在 `url` 的 server 会识别为 HTTP。

### `McpSchemaSanitizerTest`

验证：

- `$schema`、`$id` 等高级字段会被移除。
- `anyOf` 会被折叠到 description。
- 缺失 type 时补 `object`。
- 非 object Schema 会被包装成 object。

### `McpServerManagerTest`

使用 fake transport 模拟 MCP server。

验证：

- manager 可以启动 server。
- `tools/list` 返回的工具能注册到 `ToolRegistry`。
- 工具名符合 `mcp__fake__echo` 格式。
- 调用注册后的工具会转发到 MCP `tools/call`。
- 状态表中会显示 ready。

## 设计取舍

### 为什么 MCP 工具要加命名空间

内置工具已经有 `read_file`、`write_file` 等名称，很多 MCP server 也会暴露同名工具。使用 `mcp__server__tool` 可以避免冲突，同时让安全策略能用前缀统一识别 MCP 工具。

### 为什么启动失败不终止 CLI

MCP 是扩展能力，不应影响基础 Agent 使用。某个 server 启动失败时只把该 server 标记为 ERROR，其他 server 和内置工具继续可用。

### 为什么 Schema 要清洗

LLM Function Calling 对复杂 JSON Schema 的支持并不总是稳定。清洗后的 Schema 更简单，能降低模型生成错误参数的概率。

### 为什么 stdio 要三段式关闭

stdio server 是本地子进程。只调用强杀可能导致资源没有释放；只关闭 stdin 又可能遇到进程不退出。三段式策略兼顾优雅退出和兜底清理。

### 为什么 MCP 默认走 HITL

MCP 工具来自第三方 server，能力边界由 server 决定。它可能读写本地文件、访问远程服务或执行副作用操作。默认审批更符合 Bruce CLI 已有的安全模型。
