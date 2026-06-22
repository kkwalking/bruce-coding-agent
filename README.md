# Bruce CLI

Bruce CLI 是一个单模块 Maven 项目，完整集成以下 Agent 能力：

- ReAct + Tool Call
- Plan-and-Execute DAG
- 短期记忆、长期记忆与上下文压缩
- Multi-Agent 与 SubAgent
- SQLite + Embedding 代码 RAG
- WebSearch + WebFetch 联网搜索与网页抓取
- MCP stdio / Streamable HTTP 工具接入
- HITL 人工审批
- ReAct、Plan、Multi-Agent 并行执行

## 环境要求

- JDK 17+
- Maven 3.9+
- DeepSeek API Key
- 可选：智谱 GLM API Key，用于联网搜索（`GLM_API_KEY`，不复用 `DEEPSEEK_API_KEY`）
- 可选：运行 Ollama，并安装 `nomic-embed-text` 以使用默认 RAG 配置

## 构建与运行

```bash
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY
# 如需联网搜索，额外填入 GLM_API_KEY

mvn clean test
mvn package
java -jar target/bruce-cli-1.0.0-SNAPSHOT-all.jar
```

程序以当前目录作为默认工具工作区。长期记忆和 RAG 索引默认保存在：

```text
~/.brucecli/memory/long_term_memory.json
~/.brucecli/rag/codebase.db
```

可通过 `BRUCECLI_MEMORY_DIR`、`BRUCECLI_RAG_DIR`，或对应 JVM 参数
`brucecli.memory.dir`、`brucecli.rag.dir` 修改存储位置。

## CLI 命令

```text
/react                  切换 ReAct 模式
/plan                   切换 Plan-and-Execute 模式
/multi                  切换 Multi-Agent 模式

/memory on|off|status
/memory save <内容>
/memory search <查询>

/rag on|off|status
/index [path]
/search <query>
/graph <name>

/web on|off|status
/web search <query>
/web fetch <url>

/mcp
/mcp restart <name>
/mcp logs <name>
/mcp disable <name>
/mcp enable <name>

/hitl on|off|status
/parallel on|off|status

/status
/clear
/help
/exit
```

默认状态：ReAct、Memory、Web、HITL 和 Parallel 开启，RAG 关闭。

## 联网搜索配置

WebSearch 默认优先使用智谱搜索，单独读取 `GLM_API_KEY`：

```env
GLM_API_KEY=your_glm_api_key_here
GLM_SEARCH_ENGINE=search_std
GLM_SEARCH_CONTENT_SIZE=medium
```

也可以切换搜索引擎：

```env
WEB_SEARCH_PROVIDER=serpapi
SERPAPI_KEY=your_serpapi_key_here

# 或者使用自部署 SearXNG
WEB_SEARCH_PROVIDER=searxng
SEARXNG_URL=http://localhost:8888
```

Agent 会自动使用 `web_search` 和 `web_fetch`。手动调试时可用 `/web search <query>` 和 `/web fetch <url>`。

## MCP 配置

Bruce CLI 会加载两级 MCP 配置：

```text
~/.brucecli/mcp.json      用户级配置
.brucecli/mcp.json        项目级配置，优先级更高
```

配置格式兼容常见 MCP `mcpServers` 写法。stdio 示例：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"],
      "env": {
        "NODE_OPTIONS": "--max-old-space-size=256"
      }
    }
  }
}
```

Streamable HTTP 示例：

```json
{
  "mcpServers": {
    "zread": {
      "type": "http",
      "url": "https://open.bigmodel.cn/api/mcp/zread/mcp",
      "headers": {
        "Authorization": "Bearer ${GLM_API_KEY}"
      }
    }
  }
}
```

支持 `${PROJECT_DIR}`、`${HOME}` 和环境变量替换；当前项目 `.env` 里的值也可用于替换。MCP 工具会以 `mcp__server__tool` 形式进入 Agent 工具列表，并默认走 HITL 审批。

## 源码结构

所有功能位于同一个 Maven module 中，通过 Java package 保持边界：

```text
src/main/java/com/brucecli/
├── agent/          ReAct、Memory Agent 与 Multi-Agent 编排
├── llm/            DeepSeek/OpenAI-compatible 客户端与模型
├── tool/           工具注册、Tool Call 执行、并行工具执行与命令安全守卫
├── plan/           Plan-and-Execute DAG 与计划执行器
├── memory/         Memory 与上下文压缩
├── rag/            代码索引和混合检索
├── web/            联网搜索 Provider、网页抓取与正文提取
├── mcp/            MCP 配置、协议客户端、stdio/HTTP 传输和工具注册
├── approval/       HITL 审批
├── runtime/        运行时并发配置与线程工厂
└── integrated/     统一运行时与 CLI
```

项目只保留统一入口 `com.brucecli.integrated.cli.IntegratedMain`。
