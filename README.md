# Bruce CLI

Bruce CLI 是一个单模块 Maven 项目，完整集成以下 Agent 能力：

- ReAct + Tool Call
- Plan-and-Execute DAG
- 短期记忆、长期记忆与上下文压缩
- Multi-Agent 与 SubAgent
- SQLite + Embedding 代码 RAG
- WebSearch + WebFetch 联网搜索与网页抓取
- MCP stdio / Streamable HTTP 工具接入
- Bruce Agent Skills 渐进式工作流加载
- HITL 人工审批
- ReAct、Plan、Multi-Agent 并行执行
- LLM 多模态图片输入（`@image:` / `@clipboard`）

## 环境要求

- JDK 17+
- Maven 3.9+
- DeepSeek API Key，或其他 OpenAI-compatible LLM API Key
- 可选：智谱 GLM API Key，用于联网搜索（`GLM_API_KEY`，不复用 `DEEPSEEK_API_KEY`）
- 可选：智谱 GLM-5V，用于图片输入（可配置 `LLM_MODEL=glm-5v` 并复用 `GLM_API_KEY`）
- 可选：运行 Ollama，并安装 `nomic-embed-text` 以使用默认 RAG 配置

## 构建与运行

```bash
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY
# 如需联网搜索，额外填入 GLM_API_KEY
# 如需图片输入，配置支持视觉的 LLM，例如 GLM-5V

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

/skill list
/skill show <name>
/skill reload

$code-reviewer 审查当前代码变更

/hitl on|off|status
/parallel on|off|status

/status
/clear
/help
/exit
```

默认状态：ReAct、Memory、Web、HITL 和 Parallel 开启，RAG 关闭。

## 多模态图片输入

ReAct 模式支持在用户输入中附加图片：

```text
帮我分析这张截图 @image:./shot.png
对比两张图 @image:./before.png @image:./after.png
看看剪贴板截图 @clipboard
```

`@image:` 支持相对路径、绝对路径、`file://`，路径包含空格时用尖括号包裹：

```text
@image:<file:///Users/bruce/Desktop/path with spaces.png>
```

默认 DeepSeek 文本模型不一定支持视觉输入。可以通过通用 LLM 配置切换到支持图片的
OpenAI-compatible 模型：

```env
LLM_MODEL=glm-5v
GLM_API_KEY=your_glm_api_key_here
# 可选；glm-* 模型默认会使用这个地址
# LLM_API_URL=https://open.bigmodel.cn/api/paas/v4/chat/completions
```

也可以继续使用旧配置名：

```env
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_MODEL=deepseek-v4-flash
```

图片会先预处理为 data URL：小图直通，透明 PNG 会铺白底，超大图片会等比缩放并压缩，
同时在 Memory/RAG 中只保留不含 base64 的文字占位，避免长期记忆膨胀。

## Agent Skills

Bruce CLI 会扫描两级 Skill 目录：

```text
~/.brucecli/skills/<skill-name>/SKILL.md
<项目目录>/.brucecli/skills/<skill-name>/SKILL.md
```

同名 Skill 由项目级覆盖用户级。`SKILL.md` 使用包含 `name` 和
`description` 的 YAML frontmatter：

```markdown
---
name: java-review
description: 当需要审查 Java 代码质量和 Maven 项目结构时使用。
---

先检查构建配置，再检查源码边界和测试覆盖。
需要详细清单时读取 references/checklist.md。
```

普通任务只会先获得 Skill 的名称和描述目录。主 Agent 判断任务与某个 Skill
匹配时，会调用 `load_skill` 获取完整指令，再继续执行；不会为每条请求额外
调用一次选择模型。

也可以在同一条输入开头显式指定最多 3 个 Skill：

```text
$java-review 审查当前 Git 变更
$java-review $security-review 审查登录模块
```

显式前缀只对当前任务生效，Agent 实际收到的任务正文不包含 `$skill` 前缀。

Skill 可以携带 `references/`、`templates/` 等只读资源。Agent 通过
`load_skill` 激活 Skill 后，再通过 `read_skill_resource` 按需读取。路径
必须位于对应 Skill 目录内，单次输出最多 12,000 字符。首版不会执行 Skill
中的脚本。

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
├── skill/          Skill 扫描、选择、资源读取和任务级激活
├── approval/       HITL 审批
├── runtime/        运行时并发配置与线程工厂
└── integrated/     统一运行时与 CLI
```

项目只保留统一入口 `com.brucecli.integrated.cli.IntegratedMain`。
