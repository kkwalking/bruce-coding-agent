package com.brucecli.integrated.cli;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ModelOption;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.rag.model.IndexProgressListener;
import com.brucecli.skill.SkillDefinition;
import com.brucecli.skill.SkillLoadResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class IntegratedCommandProcessor {
    private final IntegratedRuntime runtime;
    private final PrintStream out;
    private final IndexProgressListener indexProgressListener;

    public IntegratedCommandProcessor(IntegratedRuntime runtime, PrintStream out) {
        this(runtime, out, null);
    }

    public IntegratedCommandProcessor(
        IntegratedRuntime runtime,
        PrintStream out,
        IndexProgressListener indexProgressListener
    ) {
        this.runtime = runtime;
        this.out = out;
        this.indexProgressListener = indexProgressListener;
    }

    public CommandResult handle(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return CommandResult.notHandled();
        }
        if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
            return CommandResult.exitRequested();
        }
        if (!trimmed.startsWith("/")) {
            return CommandResult.notHandled();
        }

        SlashRoot root = new SlashRoot(runtime, out, indexProgressListener);
        CommandLine commandLine = new CommandLine(root)
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setUnmatchedArgumentsAllowed(false);
        commandLine.setExecutionExceptionHandler((exception, parsed, parseResult) -> {
            root.fail("命令执行失败: " + exception.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        });
        commandLine.setParameterExceptionHandler((exception, args) -> {
            root.fail(exception.getMessage() + "\n执行 /help 查看可用命令。");
            return CommandLine.ExitCode.USAGE;
        });

        try {
            commandLine.execute(toPicocliArgs(trimmed));
            return root.result();
        } catch (Exception e) {
            return CommandResult.handled("命令解析失败: " + e.getMessage() + "\n执行 /help 查看可用命令。");
        }
    }

    public String help() {
        return SlashRoot.helpText();
    }

    private static String[] toPicocliArgs(String input) {
        String withoutSlash = input.substring(1);
        return splitCommandLine(withoutSlash).toArray(String[]::new);
    }

    private static List<String> splitCommandLine(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaped) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    @Command(
        name = "",
        mixinStandardHelpOptions = false,
        subcommands = {
            Help.class,
            Exit.class,
            Status.class,
            Clear.class,
            Session.class,
            Sessions.class,
            NewSession.class,
            Resume.class,
            Tree.class,
            React.class,
            Plan.class,
            Multi.class,
            Model.class,
            Parallel.class,
            Rag.class,
            Index.class,
            Search.class,
            Graph.class,
            Web.class,
            Mcp.class,
            Memory.class,
            Hitl.class,
            Skill.class
        }
    )
    private static class SlashRoot implements Runnable {
        private final IntegratedRuntime runtime;
        private final PrintStream out;
        private final IndexProgressListener indexProgressListener;
        private CommandResult result = CommandResult.notHandled();

        SlashRoot(IntegratedRuntime runtime, PrintStream out, IndexProgressListener indexProgressListener) {
            this.runtime = runtime;
            this.out = out;
            this.indexProgressListener = indexProgressListener;
        }

        @Override
        public void run() {
            handled(helpText());
        }

        void handled(String output) {
            result = CommandResult.handled(output == null ? "" : output);
        }

        void exit() {
            result = CommandResult.exitRequested();
        }

        void fail(String output) {
            result = CommandResult.handled(output == null ? "" : output);
        }

        CommandResult result() {
            return result;
        }

        static String helpText() {
            return """
                模式:
                  /react                 切换到 ReAct 模式
                  /plan                  切换到 Plan-and-Execute 模式
                  /multi                 切换到 Multi-Agent 模式

                模型:
                  /model                 查看或选择模型
                  /model <provider/model>
                                         切换模型，并保存为下次启动默认模型

                RAG（默认关闭）:
                  /rag on|off|status
                  /index [path]          建立索引，并将 path 设为当前工作目录
                  /search <query>        手动观察代码混合检索结果
                  /graph <name>          查看类或方法关系图谱

                Web（默认开启）:
                  /web on|off|status
                  /web search <query>    手动联网搜索，使用 GLM_API_KEY 等独立配置
                  /web fetch <url>       抓取网页正文

                MCP:
                  /mcp                   查看 MCP server 状态
                  /mcp restart <name>    重启某个 server
                  /mcp logs <name>       查看某个 server 的 stderr 日志
                  /mcp disable <name>    禁用某个 server
                  /mcp enable <name>     启用某个 server

                Memory:
                  /memory status
                  /memory save <内容>
                  /memory search <查询>

                HITL（默认开启）:
                  /hitl on|off|status

                Parallel（默认开启）:
                  /parallel on|off|status

                Skill:
                  /skill list            列出已加载 Skill
                  /skill show <name>     查看 Skill 元数据和完整指令
                  /skill reload          重新扫描用户级和项目级 Skill
                  $<skill> <任务>         在同一条输入中显式使用 Skill

                多模态:
                  @image:<path>          在 ReAct 输入中附加图片，path 支持相对路径、绝对路径和 file://
                  @clipboard             附加 macOS 剪贴板中的 PNG 图片

                通用:
                  /status                查看统一运行状态
                  /session               查看当前 session
                  /sessions              列出当前工作目录最近 session
                  /new                   新建 session
                  /resume <id|path>      恢复指定 session
                  /tree [entryId]        查看或切换当前 session 树节点
                  /clear                 开启新 session，保留长期记忆和 RAG 索引
                  /help
                  /exit
                """;
        }
    }

    @Command(name = "help", aliases = {"?"})
    private static class Help implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.handled(SlashRoot.helpText());
        }
    }

    @Command(name = "exit", aliases = {"quit"})
    private static class Exit implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.exit();
        }
    }

    @Command(name = "status")
    private static class Status implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.handled(root.runtime.status().toDisplayString());
        }
    }

    @Command(name = "clear")
    private static class Clear implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.runtime.clearSession();
            root.handled("已开启新 session，并清空短期记忆、临时对话和 HITL 本会话放行记录。");
        }
    }

    @Command(name = "session")
    private static class Session implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.handled(root.runtime.sessionStatus());
        }
    }

    @Command(name = "sessions")
    private static class Sessions implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.handled(root.runtime.sessionList());
        }
    }

    @Command(name = "new")
    private static class NewSession implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.runtime.newSession();
            root.handled("已新建 session。");
        }
    }

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

    @Command(name = "tree")
    private static class Tree implements Runnable {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0", arity = "0..1", description = "entry id 前缀")
        private String entryId;

        @Override
        public void run() {
            if (entryId == null || entryId.isBlank()) {
                root.handled(root.runtime.sessionTree());
                return;
            }
            root.runtime.selectSessionLeaf(entryId);
            root.handled("已切换 active leaf。\n" + root.runtime.sessionTree());
        }
    }

    @Command(name = "react")
    private static class React implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.runtime.switchMode(AgentMode.REACT);
            root.handled("已切换到 ReAct 模式。");
        }
    }

    @Command(name = "plan")
    private static class Plan implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.runtime.switchMode(AgentMode.PLAN);
            root.handled("已切换到 Plan-and-Execute 模式。");
        }
    }

    @Command(name = "multi", aliases = {"multi-agent"})
    private static class Multi implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.runtime.switchMode(AgentMode.MULTI);
            root.handled("已切换到 Multi-Agent 模式。");
        }
    }

    @Command(name = "model")
    private static class Model implements Runnable {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0", arity = "0..1", description = "provider/model 或唯一模型名")
        private String selector;

        @Override
        public void run() {
            if (selector == null || selector.isBlank()) {
                root.handled(renderModelList(root.runtime));
                return;
            }
            ModelOption selected = root.runtime.switchModel(selector);
            root.handled("已切换到模型: " + selected.display());
        }
    }

    @Command(name = "parallel", aliases = {"concurrency"})
    private static class Parallel implements Runnable {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0", arity = "0..1", description = "on|off|status")
        private String action = "status";

        @Override
        public void run() {
            switch (normalized(action)) {
                case "on" -> {
                    root.runtime.setParallelEnabled(true);
                    root.handled("Parallel 已开启；ReAct 工具、Plan DAG 和 Multi-Agent Worker 将使用批次并行。");
                }
                case "off" -> {
                    root.runtime.setParallelEnabled(false);
                    root.handled("Parallel 已关闭；后续任务使用串行对照路径。");
                }
                case "status" -> root.handled("Parallel 当前状态: " + (root.runtime.parallelEnabled() ? "开启" : "关闭"));
                default -> throw new CommandLine.ParameterException(spec.commandLine(), "parallel 只支持 on、off 或 status");
            }
        }

        @Spec CommandSpec spec;
    }

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
                    root.handled("RAG 已开启；现在可以执行 /index，并向 Agent 暴露 search_code。");
                }
                case "off" -> {
                    root.runtime.setRagEnabled(false);
                    root.handled("RAG 已关闭；search_code 和 RAG prompt 已移除。");
                }
                case "status" -> root.handled("RAG 当前状态: " + (root.runtime.ragEnabled() ? "开启" : "关闭"));
                default -> throw new CommandLine.ParameterException(spec.commandLine(), "rag 只支持 on、off 或 status");
            }
        }
    }

    @Command(name = "index")
    private static class Index implements Callable<Void> {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0", arity = "0..1")
        private Path path;

        @Override
        public Void call() throws Exception {
            Path target = path == null ? root.runtime.workspaceRoot() : path;
            root.handled(root.runtime.index(target, root.out, root.indexProgressListener).toDisplayString());
            return null;
        }
    }

    @Command(name = "search")
    private static class Search implements Callable<Void> {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0..*", arity = "1..*")
        private String[] query;

        @Override
        public Void call() throws Exception {
            root.handled(root.runtime.searchCode(join(query), 5));
            return null;
        }
    }

    @Command(name = "graph")
    private static class Graph implements Callable<Void> {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0")
        private String name;

        @Override
        public Void call() throws Exception {
            root.handled(root.runtime.graph(name));
            return null;
        }
    }

    @Command(name = "web", subcommands = {WebSearch.class, WebFetch.class})
    private static class Web implements Runnable {
        @ParentCommand SlashRoot root;
        @Spec CommandSpec spec;
        @Parameters(index = "0", arity = "0..1", description = "on|off|status")
        private String action = "status";

        @Override
        public void run() {
            switch (normalized(action)) {
                case "on" -> {
                    root.runtime.setWebEnabled(true);
                    root.handled("Web 已开启；现在向 Agent 暴露 web_search 和 web_fetch。");
                }
                case "off" -> {
                    root.runtime.setWebEnabled(false);
                    root.handled("Web 已关闭；web_search 和 web_fetch 已移除。");
                }
                case "status" -> root.handled("Web 当前状态: " + (root.runtime.webEnabled() ? "开启" : "关闭"));
                default -> throw new CommandLine.ParameterException(spec.commandLine(), "web 只支持 on、off、status、search 或 fetch");
            }
        }
    }

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

    @Command(name = "fetch")
    private static class WebFetch implements Callable<Void> {
        @ParentCommand Web web;
        @Parameters(index = "0")
        private String url;

        @Override
        public Void call() throws Exception {
            web.root.handled(web.root.runtime.webFetch(url, 8_000));
            return null;
        }
    }

    @Command(name = "mcp", subcommands = {McpRestart.class, McpLogs.class, McpDisable.class, McpEnable.class})
    private static class Mcp implements Runnable {
        @ParentCommand SlashRoot root;
        @Parameters(index = "0", arity = "0..1", description = "status")
        private String action = "status";

        @Spec CommandSpec spec;

        @Override
        public void run() {
            if (!"status".equals(normalized(action))) {
                throw new CommandLine.ParameterException(spec.commandLine(), "mcp 只支持 status、restart、logs、disable 或 enable");
            }
            root.handled(root.runtime.mcpStatus());
        }
    }

    @Command(name = "restart")
    private static class McpRestart implements Runnable {
        @ParentCommand Mcp mcp;
        @Parameters(index = "0")
        private String name;

        @Override
        public void run() {
            mcp.root.runtime.restartMcpServer(name);
            mcp.root.handled("MCP server 已重启: " + name);
        }
    }

    @Command(name = "logs")
    private static class McpLogs implements Runnable {
        @ParentCommand Mcp mcp;
        @Parameters(index = "0")
        private String name;

        @Override
        public void run() {
            mcp.root.handled(mcp.root.runtime.mcpLogs(name));
        }
    }

    @Command(name = "disable")
    private static class McpDisable implements Runnable {
        @ParentCommand Mcp mcp;
        @Parameters(index = "0")
        private String name;

        @Override
        public void run() {
            mcp.root.runtime.disableMcpServer(name);
            mcp.root.handled("MCP server 已禁用: " + name);
        }
    }

    @Command(name = "enable")
    private static class McpEnable implements Runnable {
        @ParentCommand Mcp mcp;
        @Parameters(index = "0")
        private String name;

        @Override
        public void run() {
            mcp.root.runtime.enableMcpServer(name);
            mcp.root.handled("MCP server 已启用: " + name);
        }
    }

    @Command(name = "memory", subcommands = {MemorySave.class, MemorySearch.class})
    private static class Memory implements Runnable {
        @ParentCommand SlashRoot root;
        @Spec CommandSpec spec;
        @Parameters(index = "0", arity = "0..1", description = "status")
        private String action = "status";

        @Override
        public void run() {
            if (!"status".equals(normalized(action))) {
                throw new CommandLine.ParameterException(spec.commandLine(), "memory 只支持 status、save 或 search");
            }
            root.handled(root.runtime.memoryStatus().toMarkdown());
        }
    }

    @Command(name = "save")
    private static class MemorySave implements Callable<Void> {
        @ParentCommand Memory memory;
        @Parameters(index = "0..*", arity = "1..*")
        private String[] content;

        @Override
        public Void call() throws Exception {
            String text = join(content);
            memory.root.runtime.saveMemory(text);
            memory.root.handled("长期记忆已保存: " + text);
            return null;
        }
    }

    @Command(name = "search")
    private static class MemorySearch implements Runnable {
        @ParentCommand Memory memory;
        @Parameters(index = "0..*", arity = "1..*")
        private String[] query;

        @Override
        public void run() {
            List<MemoryEntry> entries = memory.root.runtime.searchMemory(join(query), 5);
            if (entries.isEmpty()) {
                memory.root.handled("没有找到相关长期记忆。");
                return;
            }
            memory.root.handled(entries.stream()
                .map(entry -> "- " + entry.toPromptLine())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));
        }
    }

    @Command(name = "hitl")
    private static class Hitl implements Runnable {
        @ParentCommand SlashRoot root;
        @Spec CommandSpec spec;
        @Parameters(index = "0", arity = "0..1", description = "on|off|status")
        private String action = "status";

        @Override
        public void run() {
            switch (normalized(action)) {
                case "on" -> {
                    root.runtime.setHitlEnabled(true);
                    root.handled("HITL 已开启。");
                }
                case "off" -> {
                    root.runtime.setHitlEnabled(false);
                    root.handled("HITL 已关闭。");
                }
                case "status" -> root.handled("HITL 当前状态: " + (root.runtime.hitlEnabled() ? "开启" : "关闭"));
                default -> throw new CommandLine.ParameterException(spec.commandLine(), "hitl 只支持 on、off 或 status");
            }
        }
    }

    @Command(name = "skill", subcommands = {SkillList.class, SkillShow.class, SkillReload.class})
    private static class Skill implements Runnable {
        @ParentCommand SlashRoot root;

        @Override
        public void run() {
            root.handled(renderSkillList(
                root.runtime.skills(),
                root.runtime.skillOverrides(),
                root.runtime.skillDiagnostics()
            ));
        }
    }

    @Command(name = "list")
    private static class SkillList implements Runnable {
        @ParentCommand Skill skill;

        @Override
        public void run() {
            skill.root.handled(renderSkillList(
                skill.root.runtime.skills(),
                skill.root.runtime.skillOverrides(),
                skill.root.runtime.skillDiagnostics()
            ));
        }
    }

    @Command(name = "show")
    private static class SkillShow implements Runnable {
        @ParentCommand Skill skillCommand;
        @Parameters(index = "0")
        private String name;

        @Override
        public void run() {
            SkillDefinition skill = skillCommand.root.runtime.skill(name);
            skillCommand.root.handled("""
                Skill: %s
                来源: %s
                文件: %s
                描述: %s

                %s
                """.formatted(
                    skill.name(),
                    skill.source(),
                    skill.skillFile(),
                    skill.description(),
                    skill.instructions()
                ).strip());
        }
    }

    @Command(name = "reload")
    private static class SkillReload implements Runnable {
        @ParentCommand Skill skill;

        @Override
        public void run() {
            SkillLoadResult result = skill.root.runtime.reloadSkills();
            StringBuilder output = new StringBuilder("Skill 已重新加载，共 ")
                .append(result.skills().size())
                .append(" 个有效 Skill。");
            appendSkillNotes(output, result.overrides(), result.diagnostics());
            skill.root.handled(output.toString());
        }
    }

    private static String renderSkillList(
        List<SkillDefinition> skills,
        List<String> overrides,
        List<String> diagnostics
    ) {
        StringBuilder output = new StringBuilder();
        if (skills.isEmpty()) {
            output.append("当前没有可用 Skill。");
        } else {
            output.append("已加载 Skills:\n");
            for (SkillDefinition skill : skills) {
                output.append("- ")
                    .append(skill.name())
                    .append(" [").append(skill.source()).append("] ")
                    .append(skill.description())
                    .append('\n');
            }
            if (output.charAt(output.length() - 1) == '\n') {
                output.setLength(output.length() - 1);
            }
        }
        appendSkillNotes(output, overrides, diagnostics);
        return output.toString();
    }

    private static String renderModelList(IntegratedRuntime runtime) {
        ModelOption current = runtime.currentModel();
        List<ModelOption> options = runtime.modelOptions();
        StringBuilder output = new StringBuilder("当前模型: ")
            .append(current.display())
            .append('\n')
            .append("可用模型:");
        if (options.isEmpty()) {
            output.append("\n- 无");
        } else {
            for (ModelOption option : options) {
                output.append("\n- ")
                    .append(option.display());
                if (option.matches(current)) {
                    output.append(" (当前)");
                }
            }
        }
        String savePath = runtime.modelSettingsPath();
        if (savePath != null && !savePath.isBlank()) {
            output.append("\n配置文件: ").append(savePath);
        }
        return output.toString();
    }

    private static void appendSkillNotes(
        StringBuilder output,
        List<String> overrides,
        List<String> diagnostics
    ) {
        if (!overrides.isEmpty()) {
            output.append("\n覆盖信息:\n");
            overrides.forEach(item -> output.append("- ").append(item).append('\n'));
        }
        if (!diagnostics.isEmpty()) {
            output.append("\n加载诊断:\n");
            diagnostics.forEach(item -> output.append("- ").append(item).append('\n'));
        }
        while (!output.isEmpty() && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "status" : value.trim().toLowerCase();
    }

    private static String join(String[] values) {
        return String.join(" ", values).trim();
    }
}
