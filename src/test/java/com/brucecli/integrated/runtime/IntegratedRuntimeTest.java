package com.brucecli.integrated.runtime;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.approval.HitlHandler;
import com.brucecli.config.BruceSettings;
import com.brucecli.event.BruceEvent;
import com.brucecli.event.BruceEvents;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.session.SessionManager;
import com.brucecli.tool.CommandGuard;
import com.brucecli.tool.GuardedHitlToolRegistry;
import com.brucecli.integrated.cli.IntegratedCommandProcessor;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.llm.ToolCall;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.model.IndexProgress;
import com.brucecli.web.search.WebSearchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegratedRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void startsWithExpectedDefaultsAndSwitchesModesPersistently() throws Exception {
        try (TestContext context = context()) {
            RuntimeStatus status = context.runtime.status();
            assertEquals(AgentMode.REACT, status.mode());
            assertFalse(status.ragEnabled());
            assertTrue(status.webEnabled());
            assertTrue(status.hitlEnabled());
            assertTrue(status.parallelEnabled());
            assertEquals(4, status.maxParallelism());
            assertFalse(status.toolNames().stream().anyMatch(name -> name.contains("long") && name.contains("term")));
            assertTrue(status.toolNames().contains("web_search"));
            assertTrue(status.toolNames().contains("web_fetch"));
            assertTrue(status.toolNames().contains("load_skill"));
            assertTrue(status.toolNames().contains("read_skill_resource"));
            assertFalse(status.toolNames().contains("search_code"));

            context.commands.handle("/plan");
            assertEquals(AgentMode.PLAN, context.runtime.mode());
            context.commands.handle("/react");
            assertEquals(AgentMode.REACT, context.runtime.mode());

            context.commands.handle("/parallel off");
            assertFalse(context.runtime.parallelEnabled());
            assertTrue(context.commands.handle("/parallel status").output().contains("关闭"));
            context.commands.handle("/parallel on");
            assertTrue(context.runtime.parallelEnabled());
        }
    }

    @Test
    void webSwitchControlsToolsAndPrompt() throws Exception {
        try (TestContext context = context()) {
            assertTrue(context.runtime.status().toolNames().contains("web_search"));
            assertTrue(context.runtime.status().toolNames().contains("web_fetch"));
            context.runtime.run("需要查一个最新信息");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("web_search 和 web_fetch"));

            context.commands.handle("/web off");
            assertFalse(context.runtime.status().toolNames().contains("web_search"));
            assertFalse(context.runtime.status().toolNames().contains("web_fetch"));
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("web_search 和 web_fetch"));

            String disabled = context.commands.handle("/web search Java 21").output();
            assertTrue(disabled.contains("Web 当前关闭"));
        }
    }

    @Test
    void parallelSwitchControlsPrompt() throws Exception {
        try (TestContext context = context()) {
            context.runtime.run("普通问题");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("并行执行能力"));

            context.commands.handle("/parallel off");
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("并行执行能力"));
        }
    }

    @Test
    void reactModeLoadsAgentInstructionsForEachRun() throws Exception {
        write(tempDir.resolve("home/.bruce/AGENTS.md"), "global agents v1");
        write(tempDir.resolve("AGENTS.md"), "project agents v1");
        CapturingChatClient chatClient = new CapturingChatClient(
            text("first answer"),
            text("second answer")
        );

        try (TestContext context = context(chatClient)) {
            assertEquals("first answer", context.runtime.run("第一轮"));
            assertTrue(hasSystemMessageContaining(chatClient.allMessages.get(0), "global agents v1", "project agents v1"));

            write(tempDir.resolve("home/.bruce/AGENTS.md"), "global agents v2");
            write(tempDir.resolve("AGENTS.md"), "project agents v2");

            assertEquals("second answer", context.runtime.run("第二轮"));
            List<Message> secondRunMessages = chatClient.allMessages.get(1);
            assertTrue(hasSystemMessageContaining(secondRunMessages, "global agents v2", "project agents v2"));
            assertFalse(hasSystemMessageContaining(secondRunMessages, "global agents v1"));
            assertFalse(hasSystemMessageContaining(secondRunMessages, "project agents v1"));
        }
    }

    @Test
    void planModeReceivesAgentInstructions() throws Exception {
        write(tempDir.resolve("home/.bruce/AGENTS.md"), "global plan agents");
        write(tempDir.resolve("AGENTS.md"), "project plan agents");
        CapturingChatClient chatClient = new CapturingChatClient(planResponse());

        try (TestContext context = context(chatClient)) {
            context.commands.handle("/plan");
            context.runtime.run("计划任务");

            assertTrue(hasSystemMessageContaining(
                chatClient.allMessages.get(0),
                "global plan agents",
                "project plan agents"
            ));
        }
    }

    @Test
    void sessionHistorySurvivesFeatureRebuild() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(
            text("first answer"),
            text("second answer")
        );
        try (TestContext context = context(chatClient)) {
            assertEquals("first answer", context.runtime.run("第一轮"));

            context.commands.handle("/web off");
            assertEquals("second answer", context.runtime.run("第二轮"));

            List<Message> secondTurn = chatClient.allMessages.get(1);
            assertTrue(secondTurn.stream().anyMatch(message ->
                "user".equals(message.role()) && "第一轮".equals(message.content())
            ));
            assertTrue(secondTurn.stream().anyMatch(message ->
                "assistant".equals(message.role()) && "first answer".equals(message.content())
            ));
        }
    }

    @Test
    void runtimeRestartStartsWithFreshSession() throws Exception {
        CapturingChatClient firstClient = new CapturingChatClient(text("saved answer"));
        String savedSessionId;
        try (TestContext context = context(firstClient)) {
            assertEquals("saved answer", context.runtime.run("保存这一轮"));
            savedSessionId = context.runtime.sessionContext().sessionId();
        }

        CapturingChatClient secondClient = new CapturingChatClient(text("fresh answer"));
        try (TestContext context = context(secondClient)) {
            assertNotEquals(savedSessionId, context.runtime.sessionContext().sessionId());
            assertEquals(List.of(), context.runtime.sessionContext().messages());
            assertEquals("fresh answer", context.runtime.run("继续"));

            List<Message> freshTurn = secondClient.allMessages.get(0);
            assertFalse(freshTurn.stream().anyMatch(message ->
                "user".equals(message.role()) && "保存这一轮".equals(message.content())
            ));
            assertFalse(freshTurn.stream().anyMatch(message ->
                "assistant".equals(message.role()) && "saved answer".equals(message.content())
            ));
        }
    }

    @Test
    void explicitResumeRestoresSessionHistory() throws Exception {
        CapturingChatClient firstClient = new CapturingChatClient(text("saved answer"));
        String savedSessionId;
        try (TestContext context = context(firstClient)) {
            assertEquals("saved answer", context.runtime.run("保存这一轮"));
            savedSessionId = context.runtime.sessionContext().sessionId();
        }

        CapturingChatClient secondClient = new CapturingChatClient(text("resumed answer"));
        try (TestContext context = context(secondClient)) {
            assertTrue(context.commands.handle("/resume " + savedSessionId).output().contains("已恢复 session"));
            assertEquals("resumed answer", context.runtime.run("继续"));

            List<Message> resumedTurn = secondClient.allMessages.get(0);
            assertTrue(resumedTurn.stream().anyMatch(message ->
                "user".equals(message.role()) && "保存这一轮".equals(message.content())
            ));
            assertTrue(resumedTurn.stream().anyMatch(message ->
                "assistant".equals(message.role()) && "saved answer".equals(message.content())
            ));
        }
    }

    @Test
    void manualCompactWritesCompactionAndNextRunUsesSummaryContext() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(
            text("first answer"),
            text("second answer"),
            text("manual summary"),
            text("after compact")
        );
        try (TestContext context = context(chatClient, compactionSettings(true, 12))) {
            assertEquals("first answer", context.runtime.run("第一轮"));
            assertEquals("second answer", context.runtime.run("第二轮"));

            String output = context.commands.handle("/compact 只关注实现细节").output();

            assertTrue(output.contains("已压缩 session"));
            assertTrue(chatClient.allMessages.get(2).get(1).content().contains("只关注实现细节"));
            assertTrue(context.runtime.sessionContext().messages().get(0).content().contains("manual summary"));

            assertEquals("after compact", context.runtime.run("继续"));
            List<Message> nextMessages = chatClient.allMessages.get(3);
            assertTrue(nextMessages.stream().anyMatch(message ->
                message.content() != null && message.content().contains("manual summary")
            ));
            assertFalse(nextMessages.stream().anyMatch(message ->
                "第一轮".equals(message.content())
            ));
        }
    }

    @Test
    void autoCompactsAfterRunWhenContextThresholdIsExceeded() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(10,
            textWithUsage("large answer", 20, 1),
            text("auto summary")
        );
        try (TestContext context = context(chatClient, compactionSettings(true, 1))) {
            assertEquals("large answer", context.runtime.run("第一轮"));

            assertEquals(2, chatClient.allMessages.size());
            assertTrue(context.runtime.sessionContext().messages().get(0).content().contains("auto summary"));

            String repeat = context.commands.handle("/compact").output();
            assertTrue(repeat.contains("最新节点已经是 compaction"));
            assertEquals(2, chatClient.allMessages.size());
        }
    }

    @Test
    void disabledAutoCompactionDoesNotRunAfterThreshold() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(10,
            textWithUsage("large answer", 20, 1)
        );
        try (TestContext context = context(chatClient, compactionSettings(false, 1))) {
            assertEquals("large answer", context.runtime.run("第一轮"));

            assertEquals(1, chatClient.allMessages.size());
            assertFalse(context.runtime.sessionContext().messages().stream().anyMatch(message ->
                message.content() != null && message.content().contains("session compaction summary")
            ));
        }
    }

    @Test
    void runtimePublishesRunMessageModeAndSessionEvents() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(text("event answer"));
        try (TestContext context = context(chatClient)) {
            List<BruceEvent> events = new ArrayList<>();
            context.runtime.subscribe(events::add);

            assertEquals("event answer", context.runtime.run("事件测试"));
            context.runtime.switchMode(AgentMode.PLAN);
            context.runtime.newSession();

            assertTrue(events.stream().anyMatch(BruceEvents.RunStarted.class::isInstance));
            assertTrue(events.stream().anyMatch(BruceEvents.RunCompleted.class::isInstance));
            assertTrue(events.stream().anyMatch(BruceEvents.ModeChanged.class::isInstance));
            assertTrue(events.stream().anyMatch(BruceEvents.SessionChanged.class::isInstance));
            assertTrue(events.stream()
                .filter(BruceEvents.MessageCompleted.class::isInstance)
                .map(BruceEvents.MessageCompleted.class::cast)
                .anyMatch(event -> event.durable() && "user".equals(event.message().role())));
            assertTrue(events.stream()
                .filter(BruceEvents.MessageCompleted.class::isInstance)
                .map(BruceEvents.MessageCompleted.class::cast)
                .anyMatch(event -> event.durable() && "assistant".equals(event.message().role())));
        }
    }

    @Test
    void indexPublishesProgressToListenerAndEventStream() throws Exception {
        Path project = tempDir.resolve("project");
        Path source = project.resolve("src/main/java/demo/LoginService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package demo;
            public class LoginService {
                public boolean login(String user) {
                    return user != null;
                }
            }
            """);

        try (TestContext context = context()) {
            List<BruceEvent> events = new ArrayList<>();
            List<IndexProgress> progressEvents = new ArrayList<>();
            context.runtime.subscribe(events::add);
            context.runtime.setRagEnabled(true);

            context.runtime.index(
                project,
                new PrintStream(OutputStream.nullOutputStream()),
                progressEvents::add
            );

            assertTrue(progressEvents.stream().anyMatch(progress -> "done".equals(progress.phase())));
            assertTrue(events.stream()
                .filter(BruceEvents.IndexProgressUpdated.class::isInstance)
                .map(BruceEvents.IndexProgressUpdated.class::cast)
                .anyMatch(event -> "done".equals(event.progress().phase())));
        }
    }

    @Test
    void planModeRecordsTopLevelTranscriptInSession() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(chatClient)) {
            context.commands.handle("/plan");

            String result = context.runtime.run("分析项目");

            assertTrue(result.contains("# 执行报告"));
            List<Message> messages = context.runtime.sessionContext().messages();
            assertTrue(messages.stream().anyMatch(message ->
                "user".equals(message.role()) && "分析项目".equals(message.content())
            ));
            assertTrue(messages.stream().anyMatch(message ->
                "assistant".equals(message.role()) && message.content().contains("# 执行报告")
            ));
        }
    }

    @Test
    void planModePublishesOnlyTopLevelDurableMessages() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(chatClient)) {
            List<BruceEvents.MessageCompleted> completed = new ArrayList<>();
            context.runtime.subscribe(event -> {
                if (event instanceof BruceEvents.MessageCompleted messageCompleted) {
                    completed.add(messageCompleted);
                }
            });
            context.commands.handle("/plan");

            context.runtime.run("分析项目");

            List<String> durableRoles = completed.stream()
                .filter(BruceEvents.MessageCompleted::durable)
                .map(event -> event.message().role())
                .toList();
            assertEquals(List.of("user", "assistant"), durableRoles);
        }
    }

    @Test
    void ragSwitchControlsToolAndPrompt() throws Exception {
        try (TestContext context = context()) {
            context.runtime.setRagEnabled(true);
            assertTrue(context.runtime.status().toolNames().contains("search_code"));
            context.runtime.run("代码在哪里实现？");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("search_code"));

            context.runtime.setRagEnabled(false);
            assertFalse(context.runtime.status().toolNames().contains("search_code"));
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("你额外拥有 search_code"));
        }
    }

    @Test
    void skillCommandsExposeMetadataAndExplicitPrefixAppliesToCurrentTaskOnly() throws Exception {
        writeSkill("java-review", "审查 Java 代码", "UNIQUE_REVIEW_INSTRUCTION");
        CapturingChatClient chatClient = new CapturingChatClient(
            text("first"),
            text("second")
        );
        try (TestContext context = context(chatClient)) {
            String list = context.commands.handle("/skill list").output();
            assertTrue(list.contains("java-review"));
            assertTrue(list.contains("PROJECT"));
            assertTrue(context.commands.handle("/skill show java-review").output()
                .contains("UNIQUE_REVIEW_INSTRUCTION"));
            assertTrue(context.commands.handle("/skill use java-review").output().contains("/help"));

            assertEquals("first", context.runtime.run("$java-review 第一次任务"));
            assertTrue(chatClient.allMessages.get(0).stream().anyMatch(message ->
                "system".equals(message.role())
                    && message.content().contains("UNIQUE_REVIEW_INSTRUCTION")
            ));
            String firstPrompt = renderMessages(chatClient.allMessages.get(0));
            assertFalse(firstPrompt.contains("Bruce Skill"));
            assertFalse(firstPrompt.contains("Bruce Skills"));
            assertFalse(firstPrompt.contains("Bruce Coding Agent 使用渐进式 Skill"));
            assertTrue(firstPrompt.contains("Skills provide task-specific instructions."));
            assertTrue(firstPrompt.contains("可用 Skills"));
            assertSkillToolDefinitionsAreBrandNeutral(chatClient.allTools.get(0));
            assertTrue(chatClient.allMessages.get(0).stream().anyMatch(message ->
                "user".equals(message.role()) && "第一次任务".equals(message.content())
            ));

            assertEquals("second", context.runtime.run("第二次任务"));
            List<Message> secondAgentMessages = chatClient.allMessages.get(1);
            assertFalse(secondAgentMessages.stream().anyMatch(message ->
                message.content() != null && message.content().contains("UNIQUE_REVIEW_INSTRUCTION")
            ));
            assertTrue(secondAgentMessages.stream().anyMatch(message ->
                "system".equals(message.role()) && message.content().contains("java-review")
            ));
        }
    }

    private static String renderMessages(List<Message> messages) {
        return messages.stream()
            .map(Message::content)
            .filter(content -> content != null)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    private static boolean hasSystemMessageContaining(List<Message> messages, String... values) {
        return messages.stream()
            .filter(message -> "system".equals(message.role()))
            .map(Message::content)
            .filter(content -> content != null)
            .anyMatch(content -> {
                for (String value : values) {
                    if (!content.contains(value)) {
                        return false;
                    }
                }
                return true;
            });
    }

    private static void assertSkillToolDefinitionsAreBrandNeutral(List<ToolDefinition> tools) {
        List<ToolDefinition> skillTools = tools.stream()
            .filter(tool -> tool.name().equals("load_skill") || tool.name().equals("read_skill_resource"))
            .toList();
        assertEquals(2, skillTools.size());
        for (ToolDefinition tool : skillTools) {
            assertFalse(tool.description().contains("Bruce Skill"));
            assertFalse(tool.description().contains("Bruce Skills"));
            assertFalse(tool.description().contains("Bruce"));
            assertFalse(tool.parameters().toString().contains("Bruce"));
        }
    }

    @Test
    void reactAgentProgressivelyLoadsMatchingSkillWithoutSelectorCall() throws Exception {
        writeSkill("java-review", "审查 Java 代码", "AUTO_SELECTED_INSTRUCTION");
        CapturingChatClient chatClient = new CapturingChatClient(
            new ChatResponse("", List.of(toolCall(
                "load_review",
                "load_skill",
                "{\"name\":\"java-review\"}"
            ))),
            text("done"),
            text("next")
        );
        try (TestContext context = context(chatClient)) {
            assertEquals("done", context.runtime.run("请审查 Java 代码"));
            assertEquals(2, chatClient.allMessages.size());
            assertTrue(chatClient.allMessages.get(0).stream().anyMatch(message ->
                "system".equals(message.role())
                    && message.content().contains("java-review")
                    && !message.content().contains("AUTO_SELECTED_INSTRUCTION")
            ));
            assertTrue(chatClient.allMessages.get(1).stream().anyMatch(message ->
                "tool".equals(message.role())
                    && message.content().contains("AUTO_SELECTED_INSTRUCTION")
            ));

            assertEquals("next", context.runtime.run("普通后续任务"));
            assertFalse(chatClient.allMessages.get(2).stream().anyMatch(message ->
                message.content() != null && message.content().contains("AUTO_SELECTED_INSTRUCTION")
            ));
        }
    }

    @Test
    void unknownExplicitSkillFailsBeforeCallingModel() throws Exception {
        try (TestContext context = context()) {
            IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> context.runtime.run("$missing 执行任务")
            );

            assertTrue(error.getMessage().contains("未知 Skill"));
            assertTrue(context.chatClient.allMessages.isEmpty());
        }
    }

    @Test
    void reactHistoryRedactsLoadedSkillBeforeNextTask() throws Exception {
        writeSkill("review", "代码审查", "REACT_SECRET_INSTRUCTION");
        CapturingChatClient chatClient = new CapturingChatClient(
            new ChatResponse("", List.of(toolCall(
                "load_review",
                "load_skill",
                "{\"name\":\"review\"}"
            ))),
            text("reviewed"),
            text("next")
        );
        try (TestContext context = context(chatClient)) {
            assertEquals("reviewed", context.runtime.run("审查代码"));
            assertEquals("next", context.runtime.run("后续任务"));

            List<Message> nextMessages = chatClient.allMessages.get(2);
            assertFalse(nextMessages.stream().anyMatch(message ->
                message.content() != null && message.content().contains("REACT_SECRET_INSTRUCTION")
            ));
            assertTrue(nextMessages.stream().anyMatch(message ->
                "tool".equals(message.role())
                    && message.content().contains("已从历史中移除")
            ));
        }
    }

    @Test
    void planPlannerCanOnlyReadActiveSkillResources() throws Exception {
        Path skillDirectory = writeSkill("plan-helper", "辅助制定计划", "先读取 references/guide.txt");
        Files.createDirectories(skillDirectory.resolve("references"));
        Files.writeString(skillDirectory.resolve("references/guide.txt"), "plan evidence");
        CapturingChatClient chatClient = new CapturingChatClient(
            new ChatResponse("", List.of(toolCall(
                "load_skill",
                "load_skill",
                "{\"name\":\"plan-helper\"}"
            ))),
            new ChatResponse("", List.of(toolCall(
                "read_skill",
                "read_skill_resource",
                "{\"skill\":\"plan-helper\",\"path\":\"references/guide.txt\"}"
            ))),
            text("""
                {
                  "goal": "分析",
                  "tasks": [
                    {"id": "t1", "description": "使用 Skill 资料分析", "type": "ANALYSIS", "dependencies": []}
                  ]
                }
                """)
        );
        try (TestContext context = context(chatClient)) {
            context.commands.handle("/plan");
            String result = context.runtime.run("制定分析计划");

            assertTrue(result.contains("使用 Skill 资料分析"));
            assertEquals(
                List.of("load_skill", "read_skill_resource"),
                chatClient.allTools.get(0).stream().map(ToolDefinition::name).toList()
            );
            assertTrue(chatClient.allMessages.get(2).stream().anyMatch(message ->
                "tool".equals(message.role()) && message.content().contains("plan evidence")
            ));
        }
    }

    @Test
    void reactToolCallsAreWrittenBackInOriginalOrderWithParallelExecutor() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(
            new ChatResponse("", List.of(
                toolCall("call_b", "read_file", "{\"path\":\"b.txt\"}"),
                toolCall("call_a", "read_file", "{\"path\":\"a.txt\"}")
            )),
            text("done")
        );
        try (TestContext context = context(chatClient)) {
            Files.writeString(tempDir.resolve("a.txt"), "alpha");
            Files.writeString(tempDir.resolve("b.txt"), "bravo");

            String result = context.runtime.run("读取两个文件");

            assertEquals("done", result);
            List<Message> toolMessages = chatClient.lastMessages.stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
            assertEquals(List.of("call_b", "call_a"), toolMessages.stream()
                .map(Message::toolCallId)
                .toList());
            assertTrue(toolMessages.get(0).content().contains("bravo"));
            assertTrue(toolMessages.get(1).content().contains("alpha"));
        }
    }

    @Test
    void planPromptReceivesParallelInstructionsOnlyWhenEnabled() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(chatClient)) {
            context.commands.handle("/plan");
            context.runtime.run("分析项目");
            assertTrue(chatClient.lastMessages.get(0).content().contains("并行规划提示"));
        }

        CapturingChatClient serialChatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(serialChatClient)) {
            context.commands.handle("/parallel off");
            context.commands.handle("/plan");
            context.runtime.run("分析项目");
            assertFalse(serialChatClient.lastMessages.get(0).content().contains("并行规划提示"));
        }
    }

    @Test
    void guardedToolRegistryRejectsUnsafeCommandsBeforeHitlForJsonAndMap() {
        EnabledHitlHandler handler = new EnabledHitlHandler();
        GuardedHitlToolRegistry registry = new GuardedHitlToolRegistry(
            handler,
            tempDir,
            new CommandGuard(),
            new ConcurrencyConfig(2, Duration.ofSeconds(1), 1_000)
        );

        String jsonResult = registry.executeTool(
            "execute_command",
            "{\"command\":\"find / -name pom.xml\"}"
        );
        String mapResult = registry.executeTool(
            "execute_command",
            Map.of("command", "grep -R LoginService /")
        );

        assertTrue(jsonResult.contains("命令被安全策略拒绝"));
        assertTrue(mapResult.contains("命令被安全策略拒绝"));
        assertEquals(0, handler.requestCount);

        String writeResult = registry.executeTool("write_file", Map.of(
            "path", "safe.txt",
            "content", "ok"
        ));
        assertTrue(writeResult.contains("文件已写入"));
        assertEquals(1, handler.requestCount);
    }

    @Test
    void indexingUpdatesWorkspaceAndMarksProjectIndexed() throws Exception {
        Path project = tempDir.resolve("project");
        Path source = project.resolve("src/main/java/demo/LoginService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package demo;
            public class LoginService {
                public boolean login(String user) {
                    return user != null;
                }
            }
            """);
        SessionManager oldProjectSession = SessionManager.createNew(tempDir.resolve("home"), project, AgentMode.REACT);
        oldProjectSession.appendMessage(Message.user("项目旧会话"));
        String oldProjectSessionId = oldProjectSession.currentSessionId();

        try (TestContext context = context()) {
            context.runtime.setRagEnabled(true);
            context.runtime.index(project, new PrintStream(OutputStream.nullOutputStream()));

            assertEquals(project.toAbsolutePath().normalize(), context.runtime.workspaceRoot());
            assertNotEquals(oldProjectSessionId, context.runtime.sessionContext().sessionId());
            assertFalse(context.runtime.sessionContext().messages().stream()
                .anyMatch(message -> "项目旧会话".equals(message.content())));
            assertTrue(context.runtime.status().ragIndexed());
            assertTrue(context.runtime.searchCode("LoginService login", 3).contains("LoginService"));

            context.runtime.setRagEnabled(false);
            assertTrue(context.runtime.status().ragIndexed());
            assertFalse(context.runtime.status().toolNames().contains("search_code"));
        }
    }

    private TestContext context() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient();
        return context(chatClient);
    }

    private TestContext context(CapturingChatClient chatClient) throws Exception {
        return context(chatClient, null);
    }

    private TestContext context(
        CapturingChatClient chatClient,
        BruceSettings.CompactionSettings compactionSettings
    ) throws Exception {
        IntegratedRuntime runtime = new IntegratedRuntime(
            chatClient,
            tempDir,
            new FakeEmbeddingClient(),
            tempDir.resolve("rag/codebase.db"),
            new EnabledHitlHandler(),
            WebSearchConfig.empty(),
            new ConcurrencyConfig(4, Duration.ofSeconds(2), 2_000),
            tempDir.resolve("home"),
            new PrintStream(OutputStream.nullOutputStream()),
            null,
            compactionSettings
        );
        return new TestContext(
            runtime,
            new IntegratedCommandProcessor(runtime, new PrintStream(OutputStream.nullOutputStream())),
            chatClient
        );
    }

    private Path writeSkill(String name, String description, String instructions) throws Exception {
        Path directory = tempDir.resolve(".bruce/skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s
            ---

            %s
            """.formatted(name, description, instructions));
        return directory;
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(content, List.of());
    }

    private static ChatResponse textWithUsage(String content, int inputTokens, int outputTokens) {
        return new ChatResponse("assistant", content, "", List.of(), inputTokens, outputTokens, 0);
    }

    private static BruceSettings.CompactionSettings compactionSettings(boolean enabled, int keepRecentTokens) {
        BruceSettings.CompactionSettings settings = new BruceSettings.CompactionSettings();
        settings.setEnabled(enabled);
        settings.setKeepRecentTokens(keepRecentTokens);
        settings.setReserveTokens(5);
        return settings;
    }

    private static ChatResponse planResponse() {
        return text("""
            {
              "goal": "计划任务",
              "summary": "测试计划",
              "tasks": [
                {
                  "id": "t1",
                  "description": "分析任务",
                  "type": "ANALYSIS",
                  "dependencies": []
                }
              ]
            }
            """);
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new FunctionCall(name, arguments));
    }

    private record TestContext(
        IntegratedRuntime runtime,
        IntegratedCommandProcessor commands,
        CapturingChatClient chatClient
    ) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }

    private static class CapturingChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final int maxContextWindow;
        private List<Message> lastMessages = List.of();
        private final List<List<Message>> allMessages = new java.util.ArrayList<>();
        private final List<List<ToolDefinition>> allTools = new java.util.ArrayList<>();

        CapturingChatClient(ChatResponse... responses) {
            this(0, responses);
        }

        CapturingChatClient(int maxContextWindow, ChatResponse... responses) {
            this.maxContextWindow = maxContextWindow;
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            lastMessages = List.copyOf(messages);
            allMessages.add(List.copyOf(messages));
            allTools.add(List.copyOf(tools));
            ChatResponse response = responses.poll();
            return response == null ? text("ok") : response;
        }

        @Override
        public int maxContextWindow() {
            return maxContextWindow;
        }
    }

    private static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() {
            super("ollama", "fake", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            String value = text == null ? "" : text.toLowerCase();
            return new float[] {
                value.contains("login") ? 1.0f : 0.0f,
                value.contains("service") ? 1.0f : 0.0f,
                value.contains("user") ? 1.0f : 0.0f
            };
        }
    }

    private static class EnabledHitlHandler implements HitlHandler {
        private boolean enabled = true;
        private int requestCount;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            return ApprovalResult.approve();
        }
    }
}
