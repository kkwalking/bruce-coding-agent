package com.brucecli.integrated.cli;

import com.brucecli.integrated.runtime.AgentMode;
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

            assertTrue(context.commands().handle("/multi-agent").output().contains("Multi-Agent"));
            assertEquals(AgentMode.MULTI, context.runtime().mode());

            assertTrue(context.commands().handle("/react").output().contains("ReAct"));
            assertEquals(AgentMode.REACT, context.runtime().mode());
        }
    }

    @Test
    void featureSwitchCommandsValidateActionsAndKeepStatusOutput() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertTrue(context.commands().handle("/rag on").output().contains("RAG 已开启"));
            assertTrue(context.commands().handle("/rag status").output().contains("开启"));
            assertTrue(context.commands().handle("/web off").output().contains("Web 已关闭"));
            assertTrue(context.commands().handle("/web").output().contains("关闭"));
            assertTrue(context.commands().handle("/parallel off").output().contains("Parallel 已关闭"));
            assertTrue(context.commands().handle("/hitl off").output().contains("HITL 已关闭"));

            String invalid = context.commands().handle("/rag maybe").output();
            assertTrue(invalid.contains("rag 只支持"));
            assertTrue(invalid.contains("/help"));
        }
    }

    @Test
    void nestedCommandsJoinFreeTextParameters() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            String saved = context.commands().handle("/memory save 项目 默认 使用 JDK 17").output();
            assertTrue(saved.contains("项目 默认 使用 JDK 17"));

            String found = context.commands().handle("/memory search JDK 17").output();
            assertTrue(found.contains("JDK 17"));
        }
    }

    @Test
    void missingOrUnknownSlashCommandDoesNotFallThroughToAgent() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            String missingParameter = context.commands().handle("/memory save").output();
            assertTrue(missingParameter.contains("/help"));

            String unknown = context.commands().handle("/does-not-exist").output();
            assertTrue(unknown.contains("/help"));
            assertTrue(context.commands().handle("/help").output().contains("/status"));
        }
    }

    @Test
    void indexCommandRoutesProgressToListenerAndReturnsOnlySummary() throws Exception {
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
            IntegratedCommandProcessor commands = new IntegratedCommandProcessor(
                context.runtime(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                progressEvents::add
            );
            context.runtime().setRagEnabled(true);

            CommandResult result = commands.handle("/index " + project);

            assertTrue(result.output().startsWith("索引完成: files=1,"));
            assertTrue(result.output().contains("project=" + project.toAbsolutePath().normalize()));
            assertFalse(result.output().contains("[index]"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("[index]"));
            assertTrue(progressEvents.stream().anyMatch(progress -> progress.processedFiles() == 1));
            assertTrue(progressEvents.stream().anyMatch(progress -> progress.phase().equals("done")));
        }
    }

    @Test
    void runtimeRoutesMcpStartupProgressToInjectedOutput() throws Exception {
        IntegratedCliTestSupport.writeDisabledMcpServer(tempDir, "filesystem");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (IntegratedCliTestSupport.TestContext ignored = IntegratedCliTestSupport.context(
            tempDir,
            new PrintStream(output, true, StandardCharsets.UTF_8)
        )) {
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("启动 MCP server"));
        }
    }
}
