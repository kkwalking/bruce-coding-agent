package com.brucecli.integrated.cli;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.memory.model.MemoryEntry;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

public class IntegratedCommandProcessor {
    private final IntegratedRuntime runtime;
    private final PrintStream out;

    public IntegratedCommandProcessor(IntegratedRuntime runtime, PrintStream out) {
        this.runtime = runtime;
        this.out = out;
    }

    public CommandResult handle(String input) {
        try {
            if (input.equalsIgnoreCase("/exit")
                || input.equalsIgnoreCase("exit")
                || input.equalsIgnoreCase("quit")) {
                return CommandResult.exitRequested();
            }
            if (input.equalsIgnoreCase("/help")) {
                return CommandResult.handled(help());
            }
            if (input.equalsIgnoreCase("/status")) {
                return CommandResult.handled(runtime.status().toDisplayString());
            }
            if (input.equalsIgnoreCase("/clear")) {
                runtime.clearSession();
                return CommandResult.handled("已清空临时对话、短期记忆和 HITL 本会话放行记录。");
            }
            if (input.equalsIgnoreCase("/react")) {
                runtime.switchMode(AgentMode.REACT);
                return CommandResult.handled("已切换到 ReAct 模式。");
            }
            if (input.equalsIgnoreCase("/plan")) {
                runtime.switchMode(AgentMode.PLAN);
                return CommandResult.handled("已切换到 Plan-and-Execute 模式。");
            }
            if (input.equalsIgnoreCase("/multi") || input.equalsIgnoreCase("/multi-agent")) {
                runtime.switchMode(AgentMode.MULTI);
                return CommandResult.handled("已切换到 Multi-Agent 模式。");
            }

            CommandResult parallel = handleParallel(input);
            if (parallel.handled()) {
                return parallel;
            }
            CommandResult rag = handleRag(input);
            if (rag.handled()) {
                return rag;
            }
            CommandResult web = handleWeb(input);
            if (web.handled()) {
                return web;
            }
            CommandResult memory = handleMemory(input);
            if (memory.handled()) {
                return memory;
            }
            CommandResult hitl = handleHitl(input);
            if (hitl.handled()) {
                return hitl;
            }
            return CommandResult.notHandled();
        } catch (Exception e) {
            return CommandResult.handled("命令执行失败: " + e.getMessage());
        }
    }

    public String help() {
        return """
            模式:
              /react                 切换到 ReAct 模式
              /plan                  切换到 Plan-and-Execute 模式
              /multi                 切换到 Multi-Agent 模式

            RAG（默认关闭）:
              /rag on|off|status
              /index [path]          建立索引，并将 path 设为当前工作目录
              /search <query>        手动观察代码混合检索结果
              /graph <name>          查看类或方法关系图谱

            Web（默认开启）:
              /web on|off|status
              /web search <query>    手动联网搜索，使用 GLM_API_KEY 等独立配置
              /web fetch <url>       抓取网页正文

            Memory（默认开启）:
              /memory on|off|status
              /memory save <内容>
              /memory search <查询>

            HITL（默认开启）:
              /hitl on|off|status

            Parallel（默认开启）:
              /parallel on|off|status

            通用:
              /status                查看统一运行状态
              /clear                 清空会话状态，保留长期记忆和 RAG 索引
              /help
              /exit
            """;
    }

    private CommandResult handleParallel(String input) {
        if (input.equalsIgnoreCase("/parallel on") || input.equalsIgnoreCase("/concurrency on")) {
            runtime.setParallelEnabled(true);
            return CommandResult.handled("Parallel 已开启；ReAct 工具、Plan DAG 和 Multi-Agent Worker 将使用批次并行。");
        }
        if (input.equalsIgnoreCase("/parallel off") || input.equalsIgnoreCase("/concurrency off")) {
            runtime.setParallelEnabled(false);
            return CommandResult.handled("Parallel 已关闭；后续任务使用串行对照路径。");
        }
        if (input.equalsIgnoreCase("/parallel")
            || input.equalsIgnoreCase("/parallel status")
            || input.equalsIgnoreCase("/concurrency")
            || input.equalsIgnoreCase("/concurrency status")) {
            return CommandResult.handled("Parallel 当前状态: " + (runtime.parallelEnabled() ? "开启" : "关闭"));
        }
        return CommandResult.notHandled();
    }

    private CommandResult handleRag(String input) throws Exception {
        if (input.equalsIgnoreCase("/rag on")) {
            runtime.setRagEnabled(true);
            return CommandResult.handled("RAG 已开启；现在可以执行 /index，并向 Agent 暴露 search_code。");
        }
        if (input.equalsIgnoreCase("/rag off")) {
            runtime.setRagEnabled(false);
            return CommandResult.handled("RAG 已关闭；search_code 和 RAG prompt 已移除。");
        }
        if (input.equalsIgnoreCase("/rag") || input.equalsIgnoreCase("/rag status")) {
            return CommandResult.handled("RAG 当前状态: " + (runtime.ragEnabled() ? "开启" : "关闭"));
        }
        if (input.startsWith("/index")) {
            String pathText = input.substring("/index".length()).trim();
            Path target = pathText.isBlank() ? runtime.workspaceRoot() : Path.of(pathText);
            return CommandResult.handled(runtime.index(target, out).toDisplayString());
        }
        if (input.startsWith("/search ")) {
            return CommandResult.handled(runtime.searchCode(input.substring("/search ".length()).trim(), 5));
        }
        if (input.startsWith("/graph ")) {
            return CommandResult.handled(runtime.graph(input.substring("/graph ".length()).trim()));
        }
        return CommandResult.notHandled();
    }

    private CommandResult handleWeb(String input) throws Exception {
        if (input.equalsIgnoreCase("/web on")) {
            runtime.setWebEnabled(true);
            return CommandResult.handled("Web 已开启；现在向 Agent 暴露 web_search 和 web_fetch。");
        }
        if (input.equalsIgnoreCase("/web off")) {
            runtime.setWebEnabled(false);
            return CommandResult.handled("Web 已关闭；web_search 和 web_fetch 已移除。");
        }
        if (input.equalsIgnoreCase("/web") || input.equalsIgnoreCase("/web status")) {
            return CommandResult.handled("Web 当前状态: " + (runtime.webEnabled() ? "开启" : "关闭"));
        }
        if (input.startsWith("/web search ")) {
            return CommandResult.handled(runtime.webSearch(input.substring("/web search ".length()).trim(), 5));
        }
        if (input.startsWith("/web fetch ")) {
            return CommandResult.handled(runtime.webFetch(input.substring("/web fetch ".length()).trim(), 8_000));
        }
        return CommandResult.notHandled();
    }

    private CommandResult handleMemory(String input) throws Exception {
        if (input.equalsIgnoreCase("/memory on")) {
            runtime.setMemoryEnabled(true);
            return CommandResult.handled("Memory 已开启。");
        }
        if (input.equalsIgnoreCase("/memory off")) {
            runtime.setMemoryEnabled(false);
            return CommandResult.handled("Memory 已关闭；已有长期记忆仍保留。");
        }
        if (input.equalsIgnoreCase("/memory") || input.equalsIgnoreCase("/memory status")) {
            return CommandResult.handled("Memory 当前状态: " + (runtime.memoryEnabled() ? "开启" : "关闭"));
        }
        if (input.startsWith("/memory save ")) {
            String content = input.substring("/memory save ".length()).trim();
            runtime.saveMemory(content);
            return CommandResult.handled("长期记忆已保存: " + content);
        }
        if (input.startsWith("/memory search ")) {
            String query = input.substring("/memory search ".length()).trim();
            List<MemoryEntry> entries = runtime.searchMemory(query, 5);
            if (entries.isEmpty()) {
                return CommandResult.handled("没有找到相关长期记忆。");
            }
            return CommandResult.handled(entries.stream()
                .map(entry -> "- " + entry.toPromptLine())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));
        }
        return CommandResult.notHandled();
    }

    private CommandResult handleHitl(String input) {
        if (input.equalsIgnoreCase("/hitl on")) {
            runtime.setHitlEnabled(true);
            return CommandResult.handled("HITL 已开启。");
        }
        if (input.equalsIgnoreCase("/hitl off")) {
            runtime.setHitlEnabled(false);
            return CommandResult.handled("HITL 已关闭。");
        }
        if (input.equalsIgnoreCase("/hitl") || input.equalsIgnoreCase("/hitl status")) {
            return CommandResult.handled("HITL 当前状态: " + (runtime.hitlEnabled() ? "开启" : "关闭"));
        }
        return CommandResult.notHandled();
    }
}
