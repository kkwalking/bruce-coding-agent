package com.brucecli.integrated.cli;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;
import com.brucecli.event.BruceEvents;
import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.ChatClientFactory;
import com.brucecli.rag.model.IndexProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegratedCommandProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void plainTextIsLeftForAgentButExitAliasesAreHandled() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertFalse(context.commands().handle("你好 bruce").handled());

            CommandResult exit = context.commands().handle("exit");
            assertTrue(exit.handled());
            assertTrue(exit.exit());

            CommandResult quit = context.commands().handle("quit");
            assertTrue(quit.handled());
            assertTrue(quit.exit());
        }
    }

    @Test
    void picocliCommandsPreserveExistingModeAndSwitchBehavior() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertEquals(AgentMode.REACT, context.runtime().mode());

            assertTrue(context.commands().handle("/plan").output().contains("Plan-and-Execute"));
            assertEquals(AgentMode.PLAN, context.runtime().mode());

            assertTrue(context.commands().handle("/react").output().contains("ReAct"));
            assertEquals(AgentMode.REACT, context.runtime().mode());
        }
    }

    @Test
    void featureSwitchCommandsValidateActionsAndKeepStatusOutput() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertTrue(context.commands().handle("/web off").output().contains("Web 已关闭"));
            assertTrue(context.commands().handle("/web").output().contains("关闭"));
            assertTrue(context.commands().handle("/parallel off").output().contains("Parallel 已关闭"));
            assertTrue(context.commands().handle("/hitl off").output().contains("HITL 已关闭"));

            String invalid = context.commands().handle("/web maybe").output();
            assertTrue(invalid.contains("web 只支持"));
            assertTrue(invalid.contains("/help"));
        }
    }

    @Test
    void ragSlashCommandsAreTemporarilyHiddenFromCommandSurface() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertFalse(IntegratedCommandProcessor.ragSlashCommandsEnabled());

            String help = context.commands().handle("/help").output();
            assertFalse(help.contains("/rag"));
            assertFalse(help.contains("/index"));
            assertFalse(help.contains("/search <query>"));
            assertFalse(help.contains("/graph"));

            for (String command : List.of(
                "/rag on",
                "/index " + tempDir,
                "/search LoginService",
                "/graph LoginService"
            )) {
                CommandResult result = context.commands().handle(command);
                assertTrue(result.handled());
                assertTrue(result.output().contains("/help"));
            }
            assertFalse(context.runtime().ragEnabled());
        }
    }

    @Test
    void modelCommandListsAndSwitchesModels() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            String list = context.commands().handle("/model").output();
            assertTrue(list.contains("当前模型: test-model [test]"));
            assertTrue(list.contains("glm-5.1 [glm]"));

            String switched = context.commands().handle("/model glm/glm-5.1").output();
            assertTrue(switched.contains("已切换到模型: glm-5.1 [glm]"));
            assertEquals("glm", context.runtime().currentModel().provider());
            assertEquals("glm-5.1", context.runtime().currentModel().model());

            String unknown = context.commands().handle("/model missing-model").output();
            assertTrue(unknown.contains("未知模型"));
        }
    }

    @Test
    void modelCommandPersistsDefaultModelForSwitchableClient() throws Exception {
        Path file = tempDir.resolve("setting.json");
        BruceSettings settings = new BruceSettings();
        settings.getLlm().setDefaultProvider("deepseek");
        settings.getLlm().setDefaultModel("deepseek-v4-flash");
        settings.getLlm().getProviders().put("deepseek", provider("deepseek-key"));

        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(
            tempDir,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            ChatClientFactory.create(settings, new BruceSettingsLoader(file))
        )) {
            String output = context.commands().handle("/model deepseek/deepseek-v4-pro").output();

            assertTrue(output.contains("已切换到模型: deepseek-v4-pro [deepseek]"));
            String saved = Files.readString(file);
            assertTrue(saved.contains("\"defaultProvider\" : \"deepseek\""));
            assertTrue(saved.contains("\"defaultModel\" : \"deepseek-v4-pro\""));
        }
    }

    @Test
    void modelCommandRequiresProviderWhenModelNameIsAmbiguous() throws Exception {
        BruceSettings settings = new BruceSettings();
        settings.getLlm().setDefaultProvider("glm");
        settings.getLlm().setDefaultModel("glm-5.1");
        settings.getLlm().getProviders().put("glm", provider("glm-key"));
        BruceSettings.ProviderSettings local = provider("local-key");
        local.setBaseUrl("http://localhost:9000/v1");
        local.setModels(List.of("glm-5.1"));
        settings.getLlm().getProviders().put("openai_compatiable", local);

        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(
            tempDir,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            ChatClientFactory.create(settings, new BruceSettingsLoader(tempDir.resolve("setting.json")))
        )) {
            String output = context.commands().handle("/model glm-5.1").output();

            assertTrue(output.contains("模型名重复"));
            assertTrue(output.contains("provider/model"));
        }
    }

    @Test
    void missingOrUnknownSlashCommandDoesNotFallThroughToAgent() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            String removedCommand = context.commands().handle("/" + "mem" + "ory save").output();
            assertTrue(removedCommand.contains("/help"));

            String unknown = context.commands().handle("/does-not-exist").output();
            assertTrue(unknown.contains("/help"));
            assertTrue(context.commands().handle("/help").output().contains("/status"));
            assertTrue(context.commands().handle("/help").output().contains("/compact"));
        }
    }

    @Test
    void sessionCommandsExposeCreateResumeAndTreeOperations() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            context.runtime().run("第一轮");
            String sessionId = context.runtime().sessionContext().sessionId();

            assertTrue(context.commands().handle("/session").output().contains("Session: " + sessionId));
            assertTrue(context.commands().handle("/sessions").output().contains(sessionId));
            assertTrue(context.commands().handle("/tree").output().contains("第一轮"));

            String activeLeaf = context.runtime().sessionContext().activeLeafId();
            assertTrue(context.commands().handle("/tree " + activeLeaf.substring(0, 10)).output()
                .contains("已切换 active leaf"));

            assertTrue(context.commands().handle("/resume " + sessionId.substring(0, 10)).output()
                .contains("已恢复 session"));
            assertTrue(context.commands().handle("/resume missing").output().contains("未找到 session"));

            assertTrue(context.commands().handle("/new").output().contains("已新建 session"));
            assertNotEquals(sessionId, context.runtime().sessionContext().sessionId());
        }
    }

    @Test
    void runtimeIndexRoutesProgressToListenerAndReturnsOnlySummary() throws Exception {
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
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<IndexProgress> progressEvents = new ArrayList<>();

        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(
            tempDir,
            new PrintStream(output, true, StandardCharsets.UTF_8)
        )) {
            context.runtime().setRagEnabled(true);

            String result = context.runtime().index(
                project,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                progressEvents::add
            ).toDisplayString();

            assertTrue(result.startsWith("索引完成: files=1,"));
            assertTrue(result.contains("project=" + project.toAbsolutePath().normalize()));
            assertFalse(result.contains("[index]"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("[index]"));
            assertTrue(progressEvents.stream().anyMatch(progress -> progress.processedFiles() == 1));
            assertTrue(progressEvents.stream().anyMatch(progress -> progress.phase().equals("done")));
        }
    }

    @Test
    void runtimeRoutesMcpStartupProgressToActivityEvents() throws Exception {
        IntegratedCliTestSupport.writeDisabledMcpServer(tempDir, "filesystem");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(
            tempDir,
            new PrintStream(output, true, StandardCharsets.UTF_8)
        )) {
            List<String> activities = new ArrayList<>();
            context.runtime().subscribe(event -> {
                if (event instanceof BruceEvents.Activity activity) {
                    activities.add(activity.message());
                }
            });

            context.runtime().start();

            assertTrue(activities.stream().anyMatch(message -> message.contains("启动 MCP server")));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("启动 MCP server"));
        }
    }

    private static BruceSettings.ProviderSettings provider(String apiKey) {
        BruceSettings.ProviderSettings provider = new BruceSettings.ProviderSettings();
        provider.setApiKey(apiKey);
        return provider;
    }
}
