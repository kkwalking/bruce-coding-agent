# Bruce Coding Agent

Bruce Coding Agent 是一个智能编码助手，完整集成 Agent 运行时、记忆、检索、工具接入与会话能力。

## 核心特性

### Agent Runtime

- ReAct 推理与 Tool Calling
- Plan-and-Execute：基于 DAG 的任务规划与执行
- ReAct / Plan / Multi-Agent 多执行模式
- Human-in-the-Loop 审批机制

### Memory & Context

- 短期记忆、长期记忆与上下文压缩
- JSONL 会话存储，支持 `/resume <id|path>` 恢复历史
- `/tree [entryId]` 查看会话树，并从历史节点分支续聊

### Tools & Retrieval

- 基于 SQLite + Embedding 的代码 RAG
- WebSearch / WebFetch 联网搜索与网页抓取
- 支持 MCP stdio / Streamable HTTP 工具接入
- Agent Skills 渐进式工作流加载

### Model Capability

- 多模态图片输入（`@image:` / `@clipboard`）
- `/model` 模型切换

## 环境要求

- JDK 17+
- Maven 3.9+
- 显式配置 `~/.bruce/setting.json` 中的 `llm.providers`
- 可选：在 `~/.bruce/setting.json` 中配置 `webSearch`、`embedding`、`mcp` 等能力
- 可选：运行 Ollama，并安装 `nomic-embed-text` 以使用默认 RAG 配置。rag功能默认关闭，可通过/rag开启。

## 构建与运行

```bash
# 编辑 ~/.bruce/setting.json，配置模型、搜索、Embedding 和 MCP

mvn clean test
mvn clean package
java -jar target/bruce-coding-agent-1.0.0-SNAPSHOT-all.jar
```

可配置项统一读取自 `~/.bruce/setting.json`：

```json
{
  "llm": {
    "defaultProvider": "deepseek",
    "defaultModel": "deepseek-v4-flash",
    "providers": {
      "deepseek": {
        "apiKey": "your_deepseek_key"
      },
      "glm": {
        "apiKey": "your_glm_key"
      },
      "openai_compatiable": {
        "apiKey": "your_key",
        "baseUrl": "http://localhost:9000/v1",
        "models": ["local-model"]
      }
    }
  },
  "webSearch": {
    "provider": "zhipu",
    "zhipu": {
      "apiKey": "your_zhipu_key",
      "searchEngine": "search_std",
      "contentSize": "medium",
      "endpoint": "https://open.bigmodel.cn/api/paas/v4/web_search"
    },
    "serpapi": {
      "apiKey": ""
    },
    "searxng": {
      "url": ""
    }
  },
  "embedding": {
    "provider": "ollama",
    "model": "nomic-embed-text:latest",
    "baseUrl": "http://localhost:11434",
    "apiKey": ""
  },
  "mcp": {
    "servers": {}
  },
  "variables": {
    "demoToken": "replace_me"
  }
}
```

`/model` 会列出可用模型并切换当前模型；选择成功后会写回
`defaultProvider` 和 `defaultModel`，作为下次启动默认模型。DeepSeek 和 GLM 的
可选模型由 provider 类内置；OpenAI-compatible 的可选模型来自 JSON 的 `models`。

`mvn clean package` 打包完成后可直接启动：

```bash
java -jar target/bruce-coding-agent-1.0.0-SNAPSHOT-all.jar
```

也可以一条命令完成打包并启动：

```bash
mvn clean package && java -jar target/bruce-coding-agent-1.0.0-SNAPSHOT-all.jar
```

程序以当前目录作为默认工具工作区。长期记忆和 RAG 索引固定保存在：

```text
~/.bruce/memory/long_term_memory.json
~/.bruce/rag/codebase.db
```

## Slash 命令介绍

| 分类 | 命令 | 作用 | 说明 |
| --- | --- | --- | --- |
| 模式 | `/react` | 切换到 ReAct 模式 | 适合边思考边调用工具的普通任务。 |
| 模式 | `/plan` | 切换到 Plan-and-Execute 模式 | 先规划 DAG，再按任务节点执行。 |
| 模式 | `/multi` | 切换到 Multi-Agent 模式 | 使用 Planner、Worker、Reviewer 协作完成任务。 |
| 模型 | `/model` | 查看或选择模型 | 打开可用模型列表。 |
| 模型 | `/model <provider/model>` | 切换模型 | 切换后写回 `defaultProvider` 和 `defaultModel`，作为下次启动默认模型。 |
| Memory | `/memory status` | 查看 Memory 状态 | 展示短期记忆、长期记忆和压缩状态。 |
| Memory | `/memory save <内容>` | 保存长期记忆 | 用于保存跨会话稳定事实或偏好。 |
| Memory | `/memory search <查询>` | 检索长期记忆 | 手动查看与查询相关的长期记忆。 |
| RAG | `/rag on\|off\|status` | 开关或查看代码 RAG | RAG 默认关闭。 |
| RAG | `/index [path]` | 建立代码索引 | `path` 可选；传入后会把该路径设为当前工作目录。 |
| RAG | `/search <query>` | 手动代码检索 | 观察 SQLite + Embedding 混合检索结果。 |
| RAG | `/graph <name>` | 查看代码关系图谱 | 按类名或方法名查看关系。 |
| Web | `/web on\|off\|status` | 开关或查看联网能力 | Web 默认开启。 |
| Web | `/web search <query>` | 手动联网搜索 | 使用 `~/.bruce/setting.json` 的 `webSearch` 配置。 |
| Web | `/web fetch <url>` | 抓取网页正文 | 用于调试 WebFetch 提取结果。 |
| MCP | `/mcp` | 查看 MCP server 状态 | 展示 server 是否就绪、工具数量和日志摘要。 |
| MCP | `/mcp restart <name>` | 重启 MCP server | 适合配置更新或 server 异常后手动恢复。 |
| MCP | `/mcp logs <name>` | 查看 MCP stderr 日志 | 用于排查 server 启动或运行问题。 |
| MCP | `/mcp disable <name>` | 禁用 MCP server | 运行时移除该 server 暴露的工具。 |
| MCP | `/mcp enable <name>` | 启用 MCP server | 重新加载该 server 的工具。 |
| Skill | `/skill list` | 列出已加载 Skill | 同名 Skill 会由项目级覆盖用户级。 |
| Skill | `/skill show <name>` | 查看 Skill 详情 | 展示 Skill 元数据和完整指令。 |
| Skill | `/skill reload` | 重新扫描 Skill | 重新加载用户级和项目级 Skill 目录。 |
| HITL | `/hitl on\|off\|status` | 开关或查看人工审批 | HITL 默认开启。 |
| Parallel | `/parallel on\|off\|status` | 开关或查看并行执行 | Parallel 默认开启，影响 ReAct 工具、Plan DAG 和 Multi-Agent Worker。 |
| 通用 | `/status` | 查看统一运行状态 | 汇总模式、模型、Memory、RAG、Web、MCP、HITL、并行等状态。 |
| 通用 | `/session` | 查看当前 session | 展示 session id、文件路径、active leaf、模式和消息数。 |
| 通用 | `/sessions` | 列出最近 session | 仅列出当前工作目录下的 session。 |
| 通用 | `/new` | 新建 session | 开启一个新的 JSONL session。 |
| 通用 | `/resume <id\|path>` | 恢复指定 session | 支持 session id 前缀或 JSONL 文件路径。 |
| 通用 | `/tree [entryId]` | 查看或切换 session 树节点 | 不带参数查看树；带 `entryId` 切换 active leaf，下一条输入会从该节点分支。 |
| 通用 | `/clear` | 开启新 session | 保留长期记忆和 RAG 索引。 |
| 通用 | `/help` | 查看帮助 | 展示当前可用命令。 |
| 通用 | `/exit` | 退出程序 | `exit` 和 `quit` 也可退出。 |

| 输入语法 | 作用 | 示例 |
| --- | --- | --- |
| `$<skill> <任务>` | 在同一条输入中显式使用 Skill，最多可指定 3 个。 | `$code-reviewer 审查当前代码变更` |
| `@image:<path>` | 在 ReAct 输入中附加图片，支持相对路径、绝对路径和 `file://`。 | `分析截图 @image:./shot.png` |
| `@clipboard` | 附加 macOS 剪贴板中的 PNG 图片。 | `看看剪贴板截图 @clipboard` |

默认状态：ReAct、Memory、Web、HITL 和 Parallel 开启，RAG 关闭。
Session 启动默认创建新 JSONL；需要恢复历史时使用 `/resume <id|path>` 显式选择。`/tree <entryId>` 会把 active leaf 切到历史节点，下一条输入从该节点分叉。

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

## Agent Skills

Bruce Coding Agent 会扫描两级 Skill 目录：

```text
~/.bruce/skills/<skill-name>/SKILL.md
<项目目录>/.bruce/skills/<skill-name>/SKILL.md
```

同名 Skill 由项目级覆盖用户级。

普通任务只会先获得 Skill 的名称和描述目录。LLM判断任务与某个 Skill 匹配时，会调用 `load_skill` 获取完整skill上下文，实现渐进式加载。

也可以在同一条输入开头显式指定最多 3 个 Skill：

```text
$java-review 审查当前 Git 变更
$java-review $security-review 审查登录模块
```

注意：显式前缀只对当前单次任务生效。 当前暂不支持执行 Skill 中的脚本。

## 联网搜索配置

WebSearch 读取 `~/.bruce/setting.json` 的 `webSearch`。`provider` 支持 `zhipu` / `glm` / `bigmodel`、`serpapi`、`searxng` / `searx`；未显式配置时会按已填写的 key 或 url 自动选择，最后回退到智谱。

```json
{
  "webSearch": {
    "provider": "zhipu",
    "zhipu": {
      "apiKey": "your_zhipu_key",
      "searchEngine": "search_std",
      "contentSize": "medium",
      "endpoint": "https://open.bigmodel.cn/api/paas/v4/web_search"
    },
    "serpapi": {
      "apiKey": ""
    },
    "searxng": {
      "url": ""
    }
  }
}
```

Agent 会自动使用 `web_search` 和 `web_fetch`。手动调试时可用 `/web search <query>` 和 `/web fetch <url>`。

## Embedding 配置与固定存储目录

RAG 默认使用本地 Ollama，也可以切换到 OpenAI-compatible / 智谱风格的 `/embeddings` 接口：

```json
{
  "embedding": {
    "provider": "ollama",
    "model": "nomic-embed-text:latest",
    "baseUrl": "http://localhost:11434",
    "apiKey": ""
  }
}
```

长期记忆和 RAG 数据库目录不可通过 `setting.json` 切换，统一固定为 `~/.bruce/memory/long_term_memory.json` 和 `~/.bruce/rag/codebase.db`。

## MCP 配置

Bruce Coding Agent 从 `~/.bruce/setting.json` 的 `mcp.servers` 加载 MCP server：

```json
{
  "mcp": {
    "servers": {
      "filesystem": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"],
        "env": {
          "NODE_OPTIONS": "--max-old-space-size=256"
        }
      },
      "zread": {
        "type": "http",
        "url": "https://open.bigmodel.cn/api/mcp/zread/mcp",
        "headers": {
          "Authorization": "Bearer ${llm.providers.glm.apiKey}"
        }
      }
    }
  },
  "variables": {
    "customToken": "replace_me"
  }
}
```

MCP 字符串字段支持变量替换：`${HOME}`、`${PROJECT_DIR}`、`${variables.customToken}`，以及 settings 点路径，例如 `${llm.providers.glm.apiKey}`。
