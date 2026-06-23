# Agent Architecture Mapping Checklist

## Entry and command flow

- 找到默认入口类和主循环。
- 区分 slash 命令、普通用户任务和退出路径。
- 标出命令处理器如何把操作转发给运行时。

## Runtime assembly

- 找到统一运行时如何创建 Agent、工具注册表、Planner、Executor 和 Orchestrator。
- 标出每个能力开关会触发哪些组件重建或工具变化。
- 区分完整工具注册表和受限工具注册表。

## Agent modes

- ReAct：关注对话历史、system prompt、tool call 循环和临时任务上下文。
- Memory Agent：关注 Memory context、长期记忆保存工具和短期上下文压缩。
- Plan-and-Execute：关注 Planner 生成 DAG、Executor 执行任务、失败重规划。
- Multi-Agent：关注 Planner、Worker、Reviewer 分工和并行批次。

## Tool and safety boundaries

- 标出工具从哪里注册、如何暴露给模型、如何执行。
- 检查 HITL 审批、安全守卫、路径约束、命令限制和输出截断。
- 标出第三方或外部内容进入模型的位置，例如 Web、MCP、Skill references。

## Context and state

- 会话级：对话历史、HITL 放行记录、当前模式。
- 任务级：临时 system context、Skill 激活状态、计划执行上下文。
- 持久化：长期记忆、RAG 索引、MCP 配置、Skill 文件。

## Architecture output quality

- 图中节点不要超过读者能一眼理解的数量。
- 调用链使用编号步骤，避免把异常路径混进主流程。
- 表格列出模块职责、主要输入、主要输出和状态边界。
- 最后给出“新增能力接入点”，帮助读者迁移到实现任务。
