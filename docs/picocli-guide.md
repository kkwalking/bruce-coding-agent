# Picocli 入门指南

> 本文结合 Bruce Coding Agent 项目的实际代码，讲解 picocli（v4.7.7）的核心概念和用法。

## 什么是 Picocli

[Picocli](https://picocli.info/) 是一个 Java 命令行解析框架，用注解声明命令、参数和选项，自动生成帮助信息。

核心特点：

- **注解驱动** — 用 `@Command`、`@Parameters`、`@Option` 声明命令结构
- **类型安全** — 参数自动转换成 `int`、`String`、`Path`、`enum` 等类型
- **自动帮助** — 自动生成 `--help` 和 usage 信息
- **子命令** — 天然支持多级嵌套命令
- **无外部依赖** — 单 jar，不需要任何第三方库

## 引入依赖

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.7</version>
</dependency>
```

## 核心注解

| 注解 | 用途 |
|------|------|
| `@Command` | 声明一个命令（名称、子命令、描述） |
| `@Parameters` | 声明位置参数（第几个参数，是否可选，描述） |
| `@Option` | 声明命名选项（如 `--verbose`、`-v`） |
| `@ParentCommand` | 注入父命令实例（子命令获取上层依赖） |
| `@Spec` | 注入 `CommandSpec`（可获取 picocli 元数据） |

## Bruce Coding Agent 中的实际例子

Bruce Coding Agent 的所有斜杠命令都定义在 `IntegratedCommandProcessor.java` 中，使用 picocli 的嵌套内部类模式。

### 1. 最简单的命令

```java
@Command(name = "status")
private static class Status implements Runnable {
    @ParentCommand SlashRoot root;

    @Override
    public void run() {
        root.handled(root.runtime.status().toDisplayString());
    }
}
```

**要点：**
- `@Command(name = "status")` — 声明 `/status` 命令
- 实现 `Runnable` — `run()` 无返回值，不抛受检异常
- `@ParentCommand SlashRoot root` — 注入父命令，从中拿到 `runtime`

用户输入 `/status` → picocli 匹配到 `Status` → 执行 `run()` → 显示运行状态。

### 2. 带必选参数的命令

```java
@Command(name = "resume")
private static class Resume implements Runnable {
    @ParentCommand SlashRoot root;
    @Parameters(index = "0", description = "session id 前缀或 JSONL 路径")
    private String reference;

    @Override
    public void run() {
        root.runtime.resumeSession(reference);
        root.handled("已恢复 session。\n" + root.runtime.sessionStatus());
    }
}
```

**`@Parameters` 字段说明：**
- `index = "0"` — 第 0 个位置参数（必须提供）
- `description` — 帮助信息中显示

用户输入 `/resume abc` → `reference = "abc"`。

如果不提供参数，picocli 会自动报错并显示 usage：

```
Missing required parameter: <reference>
Usage: /resume <reference>
```

### 3. 带可选参数和默认值的命令

```java
@Command(name = "rag")
private static class Rag implements Runnable {
    @ParentCommand SlashRoot root;
    @Spec CommandSpec spec;
    @Parameters(index = "0", arity = "0..1", description = "on|off|status")
    private String action = "status";

    @Override
    public void run() {
        switch (normalized(action)) {
            case "on" -> {
                root.runtime.setRagEnabled(true);
                root.handled("RAG 已开启...");
            }
            case "off" -> {
                root.runtime.setRagEnabled(false);
                root.handled("RAG 已关闭...");
            }
            case "status" ->
                root.handled("RAG 当前状态: " + (root.runtime.ragEnabled() ? "开启" : "关闭"));
            default ->
                throw new CommandLine.ParameterException(
                    spec.commandLine(), "rag 只支持 on、off 或 status");
        }
    }
}
```

**要点：**
- `arity = "0..1"` — 0 到 1 个参数，即可选
- `private String action = "status"` — 默认值为 `"status"`
- `@Spec CommandSpec spec` — 注入 picocli 元数据，用于手动抛 `ParameterException`

这实现了一个典型的三态命令：`/rag`（查看状态）、`/rag on`、`/rag off`。

### 4. 子命令（复合命令）

这是 picocli 最强大的特性之一 —— 命令嵌套子命令。

```java
// 顶层：/mcp 命令，包含子命令
@Command(name = "mcp", subcommands = {
    McpRestart.class, McpLogs.class, McpDisable.class, McpEnable.class
})
private static class Mcp implements Runnable {
    @ParentCommand SlashRoot root;

    // 当没有子命令匹配时，默认执行 run()
    @Override
    public void run() {
        if (!"status".equals(normalized(action))) {
            throw new CommandLine.ParameterException(...);
        }
        root.handled(root.runtime.mcpStatus());
    }

    // 这个字段只在无子命令时使用
    @Spec CommandSpec spec;
}

// 子命令：/mcp restart <name>
@Command(name = "restart")
private static class McpRestart implements Runnable {
    @ParentCommand Mcp mcp;     // ← 注意：注入的是 Mcp，不是 SlashRoot
    @Parameters(index = "0")
    private String name;

    @Override
    public void run() {
        // 通过 mcp.root 链式访问到 SlashRoot
        mcp.root.runtime.restartMcpServer(name);
        mcp.root.handled("MCP server 已重启: " + name);
    }
}
```

**`@ParentCommand` 链条：**

```
McpRestart
  @ParentCommand Mcp mcp
    mcp.root → SlashRoot（因为 Mcp 内部声明了 @ParentCommand SlashRoot root）
      mcp.root.runtime → IntegratedRuntime
      mcp.root.out → PrintStream
      mcp.root.handled(text) → 设置返回结果
```

类似的还有 `/web search <query>`、`/memory save <content>`、`/skill show <name>` 等：

```java
@Command(name = "web", subcommands = {WebSearch.class, WebFetch.class})
private static class Web implements Runnable { ... }

@Command(name = "search")
private static class WebSearch implements Callable<Void> {
    @ParentCommand Web web;
    @Parameters(index = "0..*", arity = "1..*")
    private String[] query;

    @Override
    public Void call() throws Exception {
        web.root.handled(web.root.runtime.webSearch(join(query), 5));
        return null;
    }
}
```

### 5. Runnable 与 Callable 的选择

| 接口 | 返回值 | 异常 | 适用场景 |
|------|--------|------|---------|
| `Runnable` | `void` | 不能抛受检异常 | 简单命令，异常内部处理 |
| `Callable<Void>` | `Void` | 可抛受检异常 | 需要 `throws Exception` 的命令 |

```java
// Runnable — 内部处理异常
@Command(name = "clear")
private static class Clear implements Runnable {
    @ParentCommand SlashRoot root;
    @Override
    public void run() {
        root.runtime.clearSession();
        root.handled("已开启新 session...");
    }
}

// Callable — 向上抛异常
@Command(name = "index")
private static class Index implements Callable<Void> {
    @ParentCommand SlashRoot root;
    @Parameters(index = "0", arity = "0..1")
    private Path path;

    @Override
    public Void call() throws Exception {
        Path target = path == null ? root.runtime.workspaceRoot() : path;
        root.handled(root.runtime.index(target, root.out, ...).toDisplayString());
        return null;
    }
}
```

项目中约定：需要调 `runtime.xxx()` 且可能抛异常的用 `Callable<Void>`，纯内部逻辑的用 `Runnable`。

### 6. 错误处理

Bruce Coding Agent 在构造 `CommandLine` 时注册了两个异常处理器：

```java
CommandLine commandLine = new CommandLine(root)
    .setCaseInsensitiveEnumValuesAllowed(true)
    .setUnmatchedArgumentsAllowed(false);

// 执行异常（如 NullPointerException）
commandLine.setExecutionExceptionHandler((exception, parsed, parseResult) -> {
    root.fail("命令执行失败: " + exception.getMessage());
    return CommandLine.ExitCode.SOFTWARE;
});

// 参数解析异常（如缺少必选参数）
commandLine.setParameterExceptionHandler((exception, args) -> {
    root.fail(exception.getMessage() + "\n执行 /help 查看可用命令。");
    return CommandLine.ExitCode.USAGE;
});
```

项目中手动抛参数异常的方式：

```java
throw new CommandLine.ParameterException(
    spec.commandLine(), "rag 只支持 on、off 或 status");
// 输出：rag 只支持 on、off 或 status
//       执行 /help 查看可用命令。
```

### 7. 将一个字符串解析成 picocli 参数

因为 Bruce Coding Agent 的输入是 TUI 中一整行字符串（如 `/mcp restart filesystem`），不是 `main` 函数的 `String[] args`，所以需要手动分割：

```java
// IntegratedCommandProcessor.java

private static String[] toPicocliArgs(String input) {
    String withoutSlash = input.substring(1);  // 去掉 "/"
    return splitCommandLine(withoutSlash).toArray(String[]::new);
}

// 支持单引号、双引号、反斜杠转义的简单分词器
private static List<String> splitCommandLine(String input) { ... }
```

然后调用：

```java
commandLine.execute(toPicocliArgs(trimmed));
```

### 8. 根命令设计

Bruce Coding Agent 用一个 `name = ""` 的根命令容纳所有子命令：

```java
@Command(
    name = "",
    subcommands = {
        Help.class, Exit.class, Status.class, Clear.class,
        Session.class, Sessions.class, NewSession.class,
        Resume.class, Tree.class, React.class, Plan.class,
        Parallel.class, Rag.class, Index.class,
        Search.class, Graph.class, Web.class, Mcp.class,
        Memory.class, Hitl.class, Skill.class
    }
)
private static class SlashRoot implements Runnable {
    private final IntegratedRuntime runtime;
    private final PrintStream out;
    // ...

    @Override
    public void run() {
        // 输入只有 "/" 时，显示帮助
        handled(helpText());
    }
}
```

根命令持有全局依赖（`runtime`、`out` 等），通过 `@ParentCommand` 逐级注入到各子命令。

## 完整处理流程

```
用户输入: /mcp restart filesystem
        │
        ▼
IntegratedCommandProcessor.handle("/mcp restart filesystem")
        │
        ├── 去掉 "/" → "mcp restart filesystem"
        ├── 分割参数 → ["mcp", "restart", "filesystem"]
        ├── commandLine.execute("mcp", "restart", "filesystem")
        │
        ▼
picocli 解析:
  ├── SlashRoot 匹配子命令 "mcp" → Mcp 类
  │   └── Mcp 匹配子命令 "restart" → McpRestart 类
  │       └── @Parameters(index="0") → name = "filesystem"
        │
        ▼
McpRestart.run():
  ├── mcp.root.runtime.restartMcpServer("filesystem")
  └── mcp.root.handled("MCP server 已重启: filesystem")
        │
        ▼
返回 CommandResult.handled(...) → TUI 显示结果
```

## 其他用到的 picocli 特性

### 别名

```java
@Command(name = "exit", aliases = {"quit"})
// 用户可输入 /exit 或 /quit
```

### 大小写不敏感枚举

```java
commandLine.setCaseInsensitiveEnumValuesAllowed(true);
```

### 自动帮助（mixinStandardHelpOptions）

`IntegratedCommandProcessor` 中关闭了默认帮助（因为 Bruce Coding Agent 有自定义的 `/help`）：

```java
@Command(name = "", mixinStandardHelpOptions = false, subcommands = {...})
```

如果需要标准 `--help` 和 `-V` 选项，可以设置为 `true`：

```java
@Command(name = "deploy", mixinStandardHelpOptions = true)
```

## 快速自定义命令模板

如果你想在 Bruce Coding Agent 中添加一个新命令，按以下模板：

```java
@Command(name = "mycmd", aliases = {"mc"})
private static class MyCmd implements Runnable {
    @ParentCommand SlashRoot root;
    @Parameters(index = "0", arity = "0..1", description = "参数说明")
    private String arg = "default";

    @Override
    public void run() {
        // 在这里写逻辑
        root.handled("mycmd 执行结果: " + arg);
    }
}
```

然后两步完成注册：

1. 在 `SlashRoot` 的 `subcommands` 中添加 `MyCmd.class`
2. 在 `SlashRoot.helpText()` 中添加到帮助文本

## 参考

- [Picocli 官方文档](https://picocli.info/)
- [Picocli GitHub](https://github.com/remkop/picocli)
- Bruce Coding Agent 实现：`src/main/java/com/brucecli/integrated/cli/IntegratedCommandProcessor.java`
