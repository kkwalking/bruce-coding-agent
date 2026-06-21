# Bruce CLI

Bruce CLI 是一个单模块 Maven 项目，完整集成以下 Agent 能力：

- ReAct + Tool Call
- Plan-and-Execute DAG
- 短期记忆、长期记忆与上下文压缩
- Multi-Agent 与 SubAgent
- SQLite + Embedding 代码 RAG
- HITL 人工审批
- ReAct、Plan、Multi-Agent 并行执行

## 环境要求

- JDK 17+
- Maven 3.9+
- DeepSeek API Key
- 可选：运行 Ollama，并安装 `nomic-embed-text` 以使用默认 RAG 配置

## 构建与运行

```bash
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY

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

/hitl on|off|status
/parallel on|off|status

/status
/clear
/help
/exit
```

默认状态：ReAct、Memory、HITL 和 Parallel 开启，RAG 关闭。

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
├── approval/       HITL 审批
├── runtime/        运行时并发配置与线程工厂
└── integrated/     统一运行时与 CLI
```

项目只保留统一入口 `com.brucecli.integrated.cli.IntegratedMain`。
