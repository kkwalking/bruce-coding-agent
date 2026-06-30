# 从 Bruce Coding Agent 学 Lanterna TUI

这份文档围绕 Bruce Coding Agent 当前已经用到的 Lanterna 特性整理，目标不是替代 Lanterna 官方手册，而是帮助你通过真实代码理解：如何把一个命令行程序做成 fullscreen TUI、如何固定底部输入框、如何处理输入事件、如何绘制候选浮层和 HITL 审批框，以及哪些地方容易踩坑。

对应核心代码：

- `src/main/java/com/brucecli/integrated/cli/IntegratedMain.java`
- `src/main/java/com/brucecli/integrated/cli/BruceTuiApp.java`
- `src/main/java/com/brucecli/render/LanternaBruceRenderer.java`
- `src/main/java/com/brucecli/approval/LanternaHitlHandler.java`
- `src/main/java/com/brucecli/integrated/cli/BruceCompletionEngine.java`
- `src/main/java/com/brucecli/integrated/cli/BruceSyntaxHighlighter.java`
- `src/test/java/com/brucecli/render/LanternaBruceRendererTest.java`

## 1. Bruce Coding Agent 的 TUI 分层

Bruce Coding Agent 当前把 Lanterna 相关职责拆成三层：

| 层 | 主要类 | 职责 |
| --- | --- | --- |
| 入口层 | `IntegratedMain` | 创建 Lanterna `Screen`，初始化 runtime，重定向输出到 TUI |
| 应用循环层 | `BruceTuiApp` | 事件循环、输入编辑、历史、补全、提交任务、滚动 |
| 渲染层 | `LanternaBruceRenderer` | 全屏绘制欢迎卡、消息区、输入框、状态栏、候选框、HITL modal |

这个拆法很重要：Lanterna 只负责终端屏幕和键盘事件，业务状态仍然放在 Bruce 自己的 runtime、command processor、completion engine 和 renderer 模型里。

## 2. 创建 Fullscreen Screen

Bruce Coding Agent 在 `IntegratedMain` 中通过 `DefaultTerminalFactory` 创建 `Screen`：

```java
DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
    .setInputTimeout(50);
try (Screen screen = terminalFactory.createScreen()) {
    LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen);
    ...
}
```

几个要点：

- `Screen` 是 Lanterna 的核心抽象，类似一个离屏缓冲区。你先写到 `Screen`，再调用 `screen.refresh()` 同步到真实终端。
- 不要强制 `setForceTextTerminal(true)`。Bruce 曾经这么做过，会导致 fullscreen 刷新帧被写进终端 scrollback，看起来像屏幕无限增长。
- `setInputTimeout(50)` 让 `pollInput()` 可以短时间等待输入，而不是永久阻塞，方便主循环检查 resize 和后台输出。

Bruce 没有保留 plain fallback：Lanterna 初始化失败就失败。这符合当前默认 TUI 模式的设计。

## 3. 进入和退出 Screen 生命周期

真正进入 fullscreen 的地方在 `BruceTuiApp.run()`：

```java
screen.startScreen();
try {
    while (!exitRequested) {
        ...
    }
} finally {
    saveHistory();
    executor.shutdownNow();
    renderer.close();
    screen.stopScreen();
}
```

`startScreen()` 通常会让 Lanterna 进入 alternate screen/fullscreen 状态。`stopScreen()` 负责恢复终端。必须放在 `finally` 中，否则异常退出时终端可能停留在异常状态。

## 4. 事件循环：不要无脑全量刷新

Bruce Coding Agent 的事件循环现在是 dirty/event driven：

```java
boolean localDirty = true;
long lastResizeCheck = 0L;

while (!exitRequested) {
    boolean resized = maybeCheckResize();
    if (localDirty || resized || renderer.consumeDirty()) {
        renderer.render(input.toString(), cursor, completions(), selectedCompletion, scrollOffset, busy);
        localDirty = false;
    }

    KeyStroke key = screen.pollInput();
    if (key == null) {
        continue;
    }
    handleKey(key, completions());
    localDirty = true;
}
```

这解决了两个常见问题：

- 闪烁：如果每 40ms 强制 `clear + refresh`，终端会明显闪。
- scrollback 增长：如果终端没有正确 fullscreen 或被强制 text terminal，固定帧刷新会变成大量输出。

Bruce 的原则是：输入变化、消息追加、状态变化、审批框变化、窗口 resize 时才重绘。

## 5. 键盘输入：KeyStroke 与 KeyType

Lanterna 把键盘事件抽象为 `KeyStroke`：

```java
KeyStroke key = screen.pollInput();
KeyType type = key.getKeyType();
Character character = key.getCharacter();
```

Bruce 处理了这些类型：

| 按键 | 行为 |
| --- | --- |
| 普通字符 | 插入到 `StringBuilder input` 的 cursor 位置 |
| Enter | 提交输入 |
| Backspace/Delete | 删除字符 |
| ArrowLeft/ArrowRight | 移动 cursor |
| ArrowUp/ArrowDown | 有候选时选择候选，否则切换历史 |
| PageUp/PageDown | 滚动消息区 |
| Tab | 应用当前补全项 |
| Ctrl-C | 清空当前输入 |
| Ctrl-D/EOF | 空输入时退出，否则删除当前字符 |
| Escape | 清空候选选择状态 |

Lanterna 不会帮你实现 readline；Bruce 自己维护：

- `StringBuilder input`
- `int cursor`
- `List<String> history`
- `int historyIndex`
- `int selectedCompletion`

这也是从 JLine 切到 Lanterna 后最大的变化：输入体验要自己建模。

## 6. 线程模型：输入线程和 worker 线程

TUI 主循环不直接跑 Agent。提交输入后，Bruce 用单线程 executor 执行业务：

```java
busy = true;
renderer.updateStatus(status("running"));
renderer.appendActivity("思考中...");
executor.submit(() -> processInput(submitted));
```

这样做的原因：

- TUI 主循环继续接收键盘事件和重绘；
- Agent、MCP、RAG、Web 等耗时任务不会阻塞界面；
- 后台线程通过 `renderer.append...()` 追加消息并标记 dirty。

Renderer 内部用 `synchronized (lock)` 和 `AtomicBoolean dirty` 保护共享状态。

## 7. Renderer：Screen.clear + TextGraphics + refresh

Bruce 的一次渲染大致是：

```java
screen.clear();
TextGraphics graphics = screen.newTextGraphics();
drawMessages(graphics, columns, messageRows, scrollOffset);
drawCompletions(graphics, columns, inputTop, completions, selectedCompletion);
drawInput(graphics, columns, inputTop, inputLine, inputBottom, input, cursor, busy);
drawStatus(graphics, columns, statusRow);
drawApproval(graphics, columns, rows);
screen.refresh();
```

Lanterna 的绘制模型很直接：

- `screen.clear()` 清空离屏 buffer；
- `TextGraphics` 提供 `putString`、颜色、背景色、SGR 样式；
- `screen.refresh()` 把 buffer 刷到终端。

Bruce 没有使用 Lanterna GUI 组件体系，而是直接绘制字符 UI。这样更接近 Claude Code CLI 这类终端产品的体验，也更容易控制输入框和消息流布局。

## 8. 固定底部布局

Bruce 的底部布局固定为 4 行：

```java
int statusRow = rows - 1;
int inputBottom = rows - 2;
int inputLine = rows - 3;
int inputTop = rows - 4;
int messageRows = Math.max(1, inputTop);
```

对应视觉结构：

```text
消息区
...
━━━━━━━━━━━━━━━━━━━━
❯ 用户正在输入
━━━━━━━━━━━━━━━━━━━━
HITL on bruce · model · mode · workspace · mcp · skills · memory
```

输入框不是写进滚动历史的文本，而是每次 render 时画在屏幕底部的临时 UI。历史 transcript 只保存提交后的用户消息和系统/助手输出。

## 9. 消息区与滚动

Renderer 内部维护消息模型：

```java
private final List<TuiMessage> messages = new ArrayList<>();
```

消息类型包括：

- `USER`
- `ASSISTANT`
- `SYSTEM`
- `ACTIVITY`

绘制时先把消息按终端宽度 wrap 成 `RenderLine`，再根据 `scrollOffset` 截取可见区域：

```java
int maxStart = Math.max(0, lines.size() - messageRows);
int start = Math.max(0, Math.min(maxStart, lines.size() - messageRows - Math.max(0, scrollOffset)));
```

`PageUp` 增加 `scrollOffset`，`PageDown` 减少它。这个滚动是 TUI 内部滚动，不依赖终端原生 scrollback。

## 10. 中文宽字符：不要用 length 当列宽

终端里的“字符数”和“显示列宽”不是一回事。中文一般占 2 列，emoji 和一些符号也可能不是 1 列。

Bruce 用 Lanterna 的工具类计算列宽：

```java
TerminalTextUtils.getColumnWidth(value)
```

典型用途：

- 计算 cursor 所在列；
- 输入高亮后推进 x 坐标；
- wrap 长文本；
- fit 截断文本；
- padRight 对齐文本。

光标位置现在这样算：

```java
String prompt = "❯ ";
int promptWidth = columnWidth(prompt);
int cursorColumn = promptWidth + columnWidth(value.substring(0, cursor));
screen.setCursorPosition(new TerminalPosition(cursorColumn, inputLine));
```

这个点非常关键。只要用 `input.length()` 算 cursor，中文输入时光标就会跑到文本中间。

## 11. 颜色与样式

Bruce 用 Lanterna 的 `TextColor.ANSI` 和 `SGR.BOLD`：

```java
graphics.setForegroundColor(TextColor.ANSI.GREEN_BRIGHT);
graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
graphics.clearModifiers();
graphics.enableModifiers(SGR.BOLD);
```

当前配色语义：

| 语义 | 颜色 |
| --- | --- |
| 普通文本 | `DEFAULT` |
| 弱化信息 | `WHITE` |
| 品牌/提示 | `YELLOW_BRIGHT` |
| OK/用户输入 | `GREEN_BRIGHT` |
| 警告/危险 | `RED_BRIGHT` |
| 信息/命令 | `CYAN_BRIGHT` |

注意：每次切换样式前最好调用 `clearModifiers()`，否则 bold、背景色等状态可能延续到后续文本。

## 12. 输入高亮：Lanterna 只负责画，规则自己写

`BruceSyntaxHighlighter` 不依赖 Lanterna。它把输入拆成 `StyledSpan`：

```java
public static List<StyledSpan> highlight(String input)
```

规则包括：

- slash 命令；
- `@image:`；
- `@clipboard`；
- 普通 `@...` 引用；
- 危险 shell 片段；
- 疑似密钥字段。

Renderer 再把 span 映射到 Lanterna 颜色：

```java
for (StyledSpan span : BruceSyntaxHighlighter.highlight(input)) {
    style(graphics, colorFor(span.style()), ...);
    graphics.putString(x, row, text);
    x += columnWidth(text);
}
```

这个设计的好处是：语法规则可以单元测试，不需要真实终端。

## 13. Slash 候选浮层

补全逻辑也不依赖 Lanterna，核心方法是：

```java
BruceCompletionEngine.complete(input, cursor, runtime)
```

它返回 `CompletionItem`，包含：

- `value`：应用到输入框的值；
- `display`：候选展示文本；
- `description`：说明；
- `group`：分组；
- `complete`：是否可作为完整补全。

Renderer 负责把候选画成浮层：

```text
┌────────────────────────────┐
│ /web    Web 工具            │
│ /mcp    MCP server 状态     │
└────────────────────────────┘
```

交互规则：

- 输入 `/` 立刻显示顶层 slash 命令；
- `/web ` 显示 web 子命令；
- `/mcp restart ` 显示 MCP server 名称；
- `/skill show ` 显示 Skill 名称；
- `$` 显示显式 Skill 调用；
- `@image:` 显示本地路径候选。

## 14. HITL 审批 Modal

Lanterna 没有强制 modal 概念。Bruce 的 modal 是 renderer 自己画的：

```java
drawApproval(graphics, columns, rows);
```

流程是：

1. `LanternaHitlHandler.requestApproval()` 被工具调用触发；
2. 调用 `renderer.requestApproval(request)`；
3. Renderer 设置 `approvalDialog` 并返回 `CompletableFuture.join()`；
4. TUI 主循环优先把按键交给 `renderer.handleApprovalKey(key)`；
5. 用户按 `y/a/n/s/m/Enter/Escape` 后完成 future；
6. 工具调用线程继续执行。

这是一种简单有效的同步审批模型：业务线程等待审批，UI 线程继续处理按键和绘制。

## 15. 输出重定向：System.out/System.err 进入 TUI

Bruce 在 TUI 启动后把标准输出和错误输出重定向到 renderer stream：

```java
PrintStream originalOut = System.out;
PrintStream originalErr = System.err;
PrintStream tuiStream = renderer.stream();
System.setOut(tuiStream);
System.setErr(tuiStream);
try {
    ...
} finally {
    System.setOut(originalOut);
    System.setErr(originalErr);
}
```

这样 MCP startup、RAG index、多 Agent progress、日志等不会破坏 fullscreen 屏幕，而是作为 activity 消息进入 TUI。

这里有一个重要坑：`OutputStream.write(int)` 收到的是字节，不是完整字符。Bruce 之前把每个 byte 直接转成 char，会导致中文乱码。正确做法是先缓存字节，按 UTF-8 解码：

```java
private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
```

## 16. Resize 处理

Bruce 主循环每 250ms 检查一次 resize：

```java
if (now - lastResizeCheck >= RESIZE_CHECK_MILLIS) {
    resized = screen.doResizeIfNecessary() != null;
    lastResizeCheck = now;
}
```

如果终端尺寸变化，就重新 render。Renderer 里也会从 `screen.getTerminalSize()` 重新计算：

- columns；
- rows；
- messageRows；
- inputTop/inputLine/inputBottom；
- statusRow。

布局不要缓存终端大小，除非你也有完善的 resize invalidation。

## 17. 测试 Lanterna TUI

Bruce 使用 Lanterna 的虚拟终端测试 renderer：

```java
DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
TerminalScreen screen = new TerminalScreen(terminal);
```

当前覆盖了这些场景：

- 输入框固定在底部；
- 输入框线是实线 `━`；
- 输入框线不会进入消息历史；
- TUI stream 能正确解码 UTF-8；
- 中文输入时 cursor 使用终端列宽；
- HITL 审批按键能完成 future。

这类测试很有价值，因为 TUI bug 经常来自细节：列宽、编码、布局行号、modal 状态。

## 18. Bruce 当前用到的 Lanterna API 速查

| API | Bruce 用法 |
| --- | --- |
| `DefaultTerminalFactory` | 创建终端和 `Screen` |
| `Screen.startScreen()` | 进入 fullscreen |
| `Screen.stopScreen()` | 退出 fullscreen |
| `Screen.pollInput()` | 非阻塞/超时读取按键 |
| `KeyStroke` | 描述一次键盘事件 |
| `KeyType` | 区分 Enter、Arrow、Backspace、EOF 等 |
| `Screen.doResizeIfNecessary()` | 检测终端 resize |
| `Screen.clear()` | 清空离屏 buffer |
| `Screen.newTextGraphics()` | 创建绘图上下文 |
| `TextGraphics.putString()` | 在指定位置绘制字符串 |
| `TextGraphics.setForegroundColor()` | 设置前景色 |
| `TextGraphics.setBackgroundColor()` | 设置背景色 |
| `TextGraphics.enableModifiers(SGR.BOLD)` | 设置粗体等样式 |
| `Screen.setCursorPosition()` | 设置真实光标位置 |
| `Screen.refresh()` | 把离屏 buffer 刷到终端 |
| `TerminalTextUtils.getColumnWidth()` | 计算终端显示列宽 |
| `DefaultVirtualTerminal` | 单元测试虚拟终端 |
| `TerminalScreen` | 基于 terminal 构造 screen |

## 19. 后续可继续学习和改进的方向

Bruce 目前只用 Lanterna 的底层 Screen API，还没有使用 GUI widgets。下一步可以继续探索：

- 候选浮层支持滚动和 selected offset；
- 输入框支持多行编辑；
- 消息区支持选择、复制、折叠工具块；
- renderer 引入局部 diff 绘制，减少全屏 `clear`；
- 用更完整的 layout model 替代手写 row 计算；
- 为 modal 增加焦点、表单和错误提示；
- 对 emoji、组合字符和 ANSI 控制序列做更强的宽度处理。

理解 Bruce 这版实现后，你基本已经掌握了 Lanterna fullscreen TUI 的核心套路：`Screen` 管生命周期，`pollInput` 管事件，业务状态自己建模，`TextGraphics` 负责绘制，`refresh` 控制输出节奏。
