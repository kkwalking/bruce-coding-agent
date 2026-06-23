package com.brucecli.agent.multi;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.agent.multi.model.MultiAgentResult;
import com.brucecli.agent.multi.model.StepStatus;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {
    @Test
    void executeRetriesWhenReviewerRejectsThenSucceeds() {
        QueueChatClient fakeClient = new QueueChatClient(
            text("""
                {
                  "goal": "实现一个功能",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "写入并说明功能",
                      "type": "GENERAL",
                      "dependencies": []
                    }
                  ]
                }
                """),
            text("第一次执行结果，缺少验证说明"),
            text("""
                {
                  "approved": false,
                  "summary": "没有说明验证方式",
                  "issues": ["缺少验证"],
                  "suggestions": ["补充验证结果"]
                }
                """),
            text("第二次执行结果，已补充验证说明"),
            text("""
                {
                  "approved": true,
                  "summary": "执行结果满足要求",
                  "issues": [],
                  "suggestions": []
                }
                """)
        );

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            fakeClient,
            new ToolRegistry(Path.of(".")),
            null,
            1,
            2,
            "",
            Duration.ofSeconds(30),
            Executors.defaultThreadFactory()
        )) {
            MultiAgentResult result = orchestrator.execute("实现一个功能", "", quiet());

            assertTrue(result.success());
            assertEquals(1, result.steps().size());
            assertEquals(StepStatus.COMPLETED, result.steps().get(0).status());
            assertEquals(2, result.steps().get(0).attempts());
            assertTrue(result.summary().contains("所有步骤已通过"));
        }
    }

    @Test
    void reviewApprovalIsConservative() {
        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            new QueueChatClient(),
            new ToolRegistry(Path.of(".")),
            null,
            1,
            2,
            "",
            Duration.ofSeconds(30),
            Executors.defaultThreadFactory()
        )) {
            assertTrue(orchestrator.parseReviewApproval("{\"approved\": true}"));
            assertTrue(orchestrator.parseReviewApproval("""
                ```json
                {"approved": true, "issues": []}
                ```
                """));
            assertFalse(orchestrator.parseReviewApproval("{\"approved\": false}"));
            assertFalse(orchestrator.parseReviewApproval("看起来还可以，但没有 JSON"));
        }
    }

    @Test
    void batchTimeoutDoesNotMarkWorkerStepAsSuccessful() {
        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            new SlowWorkerChatClient(),
            new ToolRegistry(Path.of(".")),
            null,
            1,
            0,
            "",
            Duration.ofMillis(80),
            Executors.defaultThreadFactory()
        )) {
            MultiAgentResult result = orchestrator.execute("执行慢任务", "", quiet());

            assertFalse(result.success());
            assertEquals(1, result.steps().size());
            assertEquals(StepStatus.FAILED, result.steps().get(0).status());
        }
    }

    @Test
    void workerReceivesTaskSkillContextWithoutSupplier() {
        QueueChatClient fakeClient = new QueueChatClient(
            text("""
                {
                  "goal": "执行 Skill 任务",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "按 Skill 执行",
                      "type": "GENERAL",
                      "dependencies": []
                    }
                  ]
                }
                """),
            text("worker result"),
            text("""
                {
                  "approved": true,
                  "summary": "通过",
                  "issues": [],
                  "suggestions": []
                }
                """)
        );

        try (AgentOrchestrator orchestrator = new AgentOrchestrator(
            fakeClient,
            new ToolRegistry(Path.of(".")),
            null,
            1,
            0,
            "",
            Duration.ofSeconds(30),
            Executors.defaultThreadFactory()
        )) {
            MultiAgentResult result = orchestrator.execute(
                "执行 Skill 任务",
                "",
                "TASK_SKILL_INSTRUCTION",
                quiet()
            );

            assertTrue(result.success());
            assertTrue(fakeClient.allMessages.get(1).stream().anyMatch(message ->
                "system".equals(message.role())
                    && message.content().contains("TASK_SKILL_INSTRUCTION")
            ));
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(content, List.of());
    }

    private static class QueueChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> allMessages = new ArrayList<>();

        QueueChatClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            allMessages.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("没有配置更多模型响应");
            }
            return response;
        }
    }

    private static class SlowWorkerChatClient implements ChatClient {
        private int calls;

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            calls++;
            if (calls == 1) {
                return text("""
                    {
                      "goal": "执行慢任务",
                      "steps": [
                        {
                          "id": "step_1",
                          "description": "慢速执行",
                          "type": "GENERAL",
                          "dependencies": []
                        }
                      ]
                    }
                    """);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            return text("慢速结果");
        }
    }
}
