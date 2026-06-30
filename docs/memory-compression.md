# ReAct Memory 压缩机制说明

本文说明统一 ReAct `Agent` 固定集成 Memory 后的短期记忆压缩机制，并用一个具体用户输入例子串起完整流程。相关代码主要在：

- `src/main/java/com/brucecli/agent/Agent.java`
- `src/main/java/com/brucecli/agent/ReactMemoryCoordinator.java`
- `src/main/java/com/brucecli/memory/core/ConversationMemory.java`
- `src/main/java/com/brucecli/memory/core/MemoryManager.java`
- `src/main/java/com/brucecli/memory/compress/LlmContextCompressor.java`

## 一句话理解

ReAct Agent 会保留自己的工具调用历史，同时通过 `ReactMemoryCoordinator` 把最近几条消息写入短期记忆，把更早的消息先放入 `pendingCompression`，再在构建上下文时压缩成 `SUMMARY` 摘要。

下一轮调用 LLM 时，模型看到的是：

1. 和当前问题相关的长期记忆；
2. 历史对话压缩后的摘要；
3. 最近几条短期记忆；
4. 当前用户输入。

这样既能保留关键上下文，又能避免 token 随对话轮数一直增长。

## 默认参数

在 `IntegratedMain` 中，运行时使用：

```java
new MemoryManager(
    new ConversationMemory(8_000),
    new LongTermMemory(),
    new LlmContextCompressor(chatClient)
)
```

`ConversationMemory` 和 `MemoryManager` 使用同一组默认压缩参数：

- `compressionThreshold = 0.80`：短期记忆使用率达到 80% 时，把较早消息移入 `pendingCompression`。
- `keepRecentAfterCompression = 3`：即使达到 80%，也至少保留最近 3 条短期记忆原文。
- `relevantLimit = 5`：每轮最多注入 5 条相关长期记忆。
- `recentLimit = 8`：每轮最多注入 8 条最近短期记忆。

`TokenEstimator` 只是粗略估算 token：中文约 1.5 字一个 token，英文约 4 个字符一个 token。

## 压缩从哪里触发

当前实现已经把触发机制统一到 `ConversationMemory.store()`。

每次 Agent 记录用户消息、助手回复、工具结果或压缩摘要时，都会调用 `store()`。写入新条目后，`ConversationMemory` 会检查短期记忆使用率：

```java
while (
    getUsageRatio() >= compressionThreshold
        && entries.size() > keepRecentAfterCompression
) {
    evictOldest();
}
```

也就是说，短期记忆达到 80% 使用率时，就开始按 FIFO 把最旧条目移入 `pendingCompression`。`maxTokens` 不再是另一套“超过 100% 才淘汰”的触发线，而是作为使用率计算的容量基准。

这里有一个重要保护：如果短期记忆已经只剩最近 `keepRecentAfterCompression` 条，就算使用率仍然超过 80%，也不会继续移除。这样可以避免一条或几条很长的新消息把当前关键上下文全部挪走。

## 什么时候真正压缩

`store()` 只负责把旧消息放进 `pendingCompression`，不会同步调用 LLM 压缩。真正的压缩发生在下一次构建 Memory 上下文时。

每次 `Agent.run()` 收到用户输入后，会通过 `ReactMemoryCoordinator` 先调用：

```java
MemoryContext memoryContext = memoryManager.buildContext(userInput);
```

`buildContext()` 的第一步是 `compressIfNeeded()`。现在它只做一件事：取出 pending 中的旧消息，有候选就压缩，没有候选就直接返回。

```java
List<MemoryEntry> candidates = conversationMemory.drainPendingCompression();
if (candidates.isEmpty()) {
    return;
}

MemoryEntry summary = compressor.compress(candidates);
conversationMemory.addCompressedSummary(summary);
```

这种分工让机制更清晰：

- `ConversationMemory.store()`：判断哪些旧短期记忆应该准备压缩。
- `MemoryManager.compressIfNeeded()`：把准备好的旧记忆压成摘要。
- `MemoryManager.buildContext()`：注入长期记忆、压缩摘要和最近短期记忆。

## 压缩器会保留什么

当前实现使用 `LlmContextCompressor`，它会把候选 `MemoryEntry` 转成类似下面的文本：

```text
[CONVERSATION/user] 用户原始输入
[CONVERSATION/assistant] 助手回复
[TOOL_RESULT/tool] 工具执行结果
```

然后发送给大模型，并要求它用中文保留这些信息：

- 用户明确偏好；
- 项目技术栈、目录、文件名、命令；
- Agent 已经完成的动作；
- 工具结果里的关键事实；
- 不保留寒暄和重复内容。

压缩结果会被包装成一条 `MemoryType.SUMMARY` 记忆，并带上 metadata：

```java
Map.of(
    "compressor", "llm",
    "sourceCount", String.valueOf(entries.size())
)
```

随后 `ConversationMemory.addCompressedSummary(summary)` 会做两件事：

1. 把摘要加入 `compressedSummaries`，方便下一轮上下文注入；
2. 调用 `store(summary)`，让摘要本身也成为短期记忆的一部分，并参与同一套 80% 阈值控制。

## 具体例子

为了更容易看清楚过程，下面用较小的 token 预算来示范。真实 CLI 默认是 `8_000`，这里假设短期记忆预算只有 `120` tokens，压缩阈值是 80%，也就是达到 `96` tokens 左右就会把旧消息移入 `pendingCompression`。

### 第 1 轮用户输入

用户输入：

```text
以后 bruce 项目的 Java 代码都默认用 JDK 17 和 Maven，终端文案统一使用 bruce。
现在帮我给 Memory 模块加一个 /memory status 展示。
```

执行流程：

1. `Agent.run()` 先通过 `ReactMemoryCoordinator` 调用 `memoryManager.buildContext(userInput)`。
2. 因为这是早期对话，`pendingCompression` 还是空的，`compressIfNeeded()` 不会产生摘要。
3. Agent 把 Memory 上下文和当前用户输入发给 LLM。
4. 任务执行结束后，Agent 调用 `rememberUserMessage()` 和 `rememberAssistantMessage()` 保存本轮对话。
5. 每次保存都会进入 `ConversationMemory.store()`，如果短期记忆达到 80%，较早消息会被移入 `pendingCompression`。
6. 如果模型识别出“以后默认用 JDK 17 和 Maven”是长期偏好，还可能调用 `save_long_term_memory`，写入 `LongTermMemory`。

此时短期记忆中可能有：

```text
[CONVERSATION/user] 以后 bruce 项目的 Java 代码都默认用 JDK 17 和 Maven...
[CONVERSATION/assistant] 已为 Memory 模块补充 /memory status...
```

### 后续几轮对话变长

用户继续输入：

```text
继续把状态里展示压缩次数、待压缩条数和最近一次注入 token 数。
```

Agent 可能会记录更多内容：

```text
[CONVERSATION/user] 继续把状态里展示压缩次数...
[CONVERSATION/assistant] 模型请求调用工具: [read_file, write_file]
[TOOL_RESULT/tool] 读取 MemoryStatus.java 成功...
[TOOL_RESULT/tool] 写入 IntegratedCommandProcessor.java 成功...
[CONVERSATION/assistant] 已完成状态展示字段补充...
```

随着短期记忆越来越长，`store()` 发现使用率达到 80%，就会把最旧消息移出 `entries`，放进 `pendingCompression`。例如：

```text
pendingCompression:
- [CONVERSATION/user] 以后 bruce 项目的 Java 代码都默认用 JDK 17 和 Maven...
- [CONVERSATION/assistant] 已为 Memory 模块补充 /memory status...

entries:
- [TOOL_RESULT/tool] 读取 MemoryStatus.java 成功...
- [TOOL_RESULT/tool] 写入 IntegratedCommandProcessor.java 成功...
- [CONVERSATION/assistant] 已完成状态展示字段补充...
```

这些旧消息已经不再作为原文短期记忆保留，但它们还没有丢失，正在等待压缩。

### 下一轮用户输入触发摘要生成

用户再输入：

```text
继续优化 Memory 压缩逻辑，把用户偏好、项目约定和已完成动作都保留下来。
```

这一次 `Agent.run()` 仍然先通过 `ReactMemoryCoordinator` 调用 `buildContext(userInput)`。进入 `compressIfNeeded()` 后：

1. `drainPendingCompression()` 取出前面待压缩的旧消息，并清空待压缩队列；
2. `MemoryManager` 不再额外根据 80% 主动移除短期记忆；
3. 取出的候选内容一起交给 `LlmContextCompressor`。

压缩器收到的候选内容可能是：

```text
[CONVERSATION/user] 以后 bruce 项目的 Java 代码都默认用 JDK 17 和 Maven，终端文案统一使用 bruce。现在帮我给 Memory 模块加一个 /memory status 展示。
[CONVERSATION/assistant] 已为 Memory 模块补充 /memory status 展示。
[CONVERSATION/user] 继续把状态里展示压缩次数、待压缩条数和最近一次注入 token 数。
```

它可能生成这样的摘要：

```text
用户偏好和项目约定：bruce 项目的 Java 代码默认使用 JDK 17 和 Maven，终端文案统一使用 bruce。
已完成动作：Memory 模块已补充 /memory status 展示，并加入压缩次数、待压缩条数、最近一次注入 token 数等状态字段。
关键文件：MemoryStatus.java、IntegratedCommandProcessor.java。
```

这条摘要会作为 `SUMMARY` 被放入 `compressedSummaries`，同时也重新进入短期记忆。

### 压缩后的下一轮上下文

压缩完成后，`buildContext()` 会组装给 LLM 的 Memory prompt。结构大致是：

```text
以下是 Memory 系统检索到的上下文。它们可能有用，但如果和当前任务无关，可以忽略。

## 相关长期记忆
- [FACT/memory] 用户偏好：Java 项目默认使用 JDK 17 和 Maven。

## 压缩摘要
- [SUMMARY/memory] 用户偏好和项目约定：bruce 项目的 Java 代码默认使用 JDK 17 和 Maven...

## 最近短期记忆
- [CONVERSATION/user] 继续优化 Memory 压缩逻辑...
- [CONVERSATION/assistant] 模型请求调用工具: [read_file]
- [TOOL_RESULT/tool] 读取 MemoryManager.java 成功...
```

这就是压缩机制的核心收益：模型仍然知道“用户偏好、项目约定、已完成动作”，但不需要看到所有历史原文。

## 和长期记忆的区别

压缩摘要不是长期记忆。

- 压缩摘要来自短期对话历史，主要解决“当前会话太长”的问题。
- 长期记忆来自 `/memory save` 或 `save_long_term_memory`，会落盘到 `~/.bruce/memory/long_term_memory.json`，主要解决“跨会话复用”的问题。

每轮上下文会同时包含二者：长期记忆负责稳定事实，压缩摘要负责当前会话历史。

## 当前实现的边界

当前实现以 `compressionThreshold` 作为统一基准，但不会在 `store()` 里同步调用 LLM。这样可以避免每次写入消息都触发网络请求，也避免工具调用过程中出现额外的压缩调用。

如果最近几条消息本身很长，短期记忆使用率可能仍然高于 80%。这是有意保留的行为，因为最近 `keepRecentAfterCompression` 条原文通常比立刻压缩更重要。

## 观察压缩状态

`MemoryStatus` 会记录和压缩相关的运行指标：

- `pendingCompressionEntries`：当前等待压缩的旧消息数量；
- `compressedSummaryEntries`：已经生成的摘要数量；
- `compressionCount`：压缩执行次数；
- `compressedSourceEntries`：累计参与压缩的源消息数量；
- `lastContextTokens`：最近一次 Memory prompt 的估算 token 数；
- `shouldCompress`：当前是否存在待压缩消息。

这些指标可以帮助判断：短期记忆是否已经有旧消息等待压缩、压缩是否真的发生、下一轮上下文是不是已经被摘要替代。
