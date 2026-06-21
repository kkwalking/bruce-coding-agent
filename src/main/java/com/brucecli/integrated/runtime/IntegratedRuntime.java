package com.brucecli.integrated.runtime;

import com.brucecli.agent.Agent;
import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.approval.HitlHandler;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.runtime.DaemonThreadFactory;
import com.brucecli.plan.executor.ParallelPlanExecutor;
import com.brucecli.tool.ParallelToolCallExecutor;
import com.brucecli.tool.CommandGuard;
import com.brucecli.tool.GuardedHitlToolRegistry;
import com.brucecli.llm.ChatClient;
import com.brucecli.agent.memory.MemoryAwareAgent;
import com.brucecli.agent.memory.MemoryToolRegistrar;
import com.brucecli.memory.core.MemoryContext;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.agent.multi.AgentOrchestrator;
import com.brucecli.plan.agent.PlanAndExecuteAgent;
import com.brucecli.plan.executor.ExecutionPlanExecutor;
import com.brucecli.plan.executor.PlanExecutor;
import com.brucecli.plan.planner.DeepSeekPlanner;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.index.CodeIndex;
import com.brucecli.rag.model.CodeRelation;
import com.brucecli.rag.model.IndexStats;
import com.brucecli.rag.search.CodeRetriever;
import com.brucecli.rag.search.SearchResultFormatter;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.rag.store.VectorStore.SearchResult;
import com.brucecli.rag.tool.RagToolRegistrar;
import com.brucecli.web.fetch.WebFetchFormatter;
import com.brucecli.web.fetch.WebFetcher;
import com.brucecli.web.search.SearchProvider;
import com.brucecli.web.search.SearchProviderFactory;
import com.brucecli.web.search.WebSearchConfig;
import com.brucecli.web.search.WebSearchFormatter;
import com.brucecli.web.search.WebSearchResult;
import com.brucecli.web.tool.WebToolRegistrar;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 各 Agent 能力的统一装配层。
 */
public class IntegratedRuntime implements AutoCloseable {
    private static final int MAX_REVIEW_RETRIES = 2;
    private static final String PARALLEL_AGENT_INSTRUCTIONS = """
        你正在使用并行执行能力。
        当多个工具调用彼此独立时，可以在同一轮返回多个 tool_calls；程序会并行执行，并按原始顺序写回结果。
        适合并行：读取多个不同文件、列出多个目录、运行多个互不依赖的观察命令。
        不适合并行：先读再写、先创建再写入、多个任务写同一文件、后一步依赖前一步输出。
        如果存在先后依赖，请拆成多轮工具调用，或在 Plan/Multi-Agent 中显式声明依赖。
        """;
    private static final String PARALLEL_PLANNER_INSTRUCTIONS = """
        并行规划提示：
        只有真实存在先后依赖时才填写 dependencies。
        彼此独立的读取、分析、验证任务不要互相添加依赖，以便执行器把它们放入同一批并行执行。
        写同一文件、创建后再写入、验证依赖构建产物等任务必须显式写出依赖。
        """;

    private final ChatClient chatClient;
    private final MemoryManager memoryManager;
    private final EmbeddingClient embeddingClient;
    private final Path ragDbFile;
    private final HitlHandler hitlHandler;
    private final ConcurrencyConfig concurrencyConfig;
    private final WebSearchConfig webSearchConfig;

    private Path workspaceRoot;
    private AgentMode mode = AgentMode.REACT;
    private boolean memoryEnabled = true;
    private boolean ragEnabled;
    private boolean webEnabled = true;
    private boolean parallelEnabled = true;

    private GuardedHitlToolRegistry toolRegistry;
    private Agent reactAgent;
    private MemoryAwareAgent memoryAwareAgent;
    private PlanAndExecuteAgent planAgent;
    private AgentOrchestrator multiAgent;
    private ParallelToolCallExecutor parallelToolCallExecutor;
    private SearchProvider webSearchProvider;
    private WebFetcher webFetcher;

    public IntegratedRuntime(
        ChatClient chatClient,
        Path workspaceRoot,
        MemoryManager memoryManager,
        EmbeddingClient embeddingClient,
        Path ragDbFile,
        HitlHandler hitlHandler
    ) {
        this(
            chatClient,
            workspaceRoot,
            memoryManager,
            embeddingClient,
            ragDbFile,
            hitlHandler,
            WebSearchConfig.empty(),
            ConcurrencyConfig.defaults()
        );
    }

    public IntegratedRuntime(
        ChatClient chatClient,
        Path workspaceRoot,
        MemoryManager memoryManager,
        EmbeddingClient embeddingClient,
        Path ragDbFile,
        HitlHandler hitlHandler,
        ConcurrencyConfig concurrencyConfig
    ) {
        this(
            chatClient,
            workspaceRoot,
            memoryManager,
            embeddingClient,
            ragDbFile,
            hitlHandler,
            WebSearchConfig.empty(),
            concurrencyConfig
        );
    }

    public IntegratedRuntime(
        ChatClient chatClient,
        Path workspaceRoot,
        MemoryManager memoryManager,
        EmbeddingClient embeddingClient,
        Path ragDbFile,
        HitlHandler hitlHandler,
        WebSearchConfig webSearchConfig,
        ConcurrencyConfig concurrencyConfig
    ) {
        this.chatClient = chatClient;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.memoryManager = memoryManager;
        this.embeddingClient = embeddingClient;
        this.ragDbFile = ragDbFile.toAbsolutePath().normalize();
        this.hitlHandler = hitlHandler;
        this.concurrencyConfig = concurrencyConfig;
        this.webSearchConfig = webSearchConfig == null ? WebSearchConfig.empty() : webSearchConfig;
        rebuildComponents();
    }

    public String run(String input) throws Exception {
        return switch (mode) {
            case REACT -> runReact(input);
            case PLAN -> runPlan(input);
            case MULTI -> runMulti(input);
        };
    }

    public AgentMode mode() {
        return mode;
    }

    public void switchMode(AgentMode mode) {
        this.mode = mode;
    }

    public boolean memoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean enabled) {
        if (memoryEnabled == enabled) {
            return;
        }
        memoryEnabled = enabled;
        rebuildComponents();
    }

    public boolean ragEnabled() {
        return ragEnabled;
    }

    public void setRagEnabled(boolean enabled) {
        if (ragEnabled == enabled) {
            return;
        }
        ragEnabled = enabled;
        rebuildComponents();
    }

    public boolean webEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean enabled) {
        if (webEnabled == enabled) {
            return;
        }
        webEnabled = enabled;
        webSearchProvider = null;
        webFetcher = null;
        rebuildComponents();
    }

    public boolean hitlEnabled() {
        return hitlHandler.isEnabled();
    }

    public void setHitlEnabled(boolean enabled) {
        hitlHandler.setEnabled(enabled);
    }

    public boolean parallelEnabled() {
        return parallelEnabled;
    }

    public void setParallelEnabled(boolean enabled) {
        if (parallelEnabled == enabled) {
            return;
        }
        parallelEnabled = enabled;
        rebuildComponents();
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public IndexStats index(Path projectPath, PrintStream out) throws Exception {
        requireRagEnabled();
        Path target = projectPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("不是有效目录: " + target);
        }

        try (VectorStore vectorStore = new VectorStore(ragDbFile)) {
            IndexStats stats = new CodeIndex(embeddingClient, vectorStore).index(target, out);
            workspaceRoot = target;
            rebuildComponents();
            return stats;
        }
    }

    public String searchCode(String query, int topK) throws Exception {
        requireRagEnabled();
        try (VectorStore vectorStore = new VectorStore(ragDbFile)) {
            CodeRetriever retriever = new CodeRetriever(
                workspaceRoot.toString(),
                embeddingClient,
                vectorStore
            );
            List<SearchResult> results = retriever.hybridSearch(query, topK);
            return SearchResultFormatter.formatForCli(query, results);
        }
    }

    public String graph(String name) throws Exception {
        requireRagEnabled();
        try (VectorStore vectorStore = new VectorStore(ragDbFile)) {
            List<CodeRelation> relations = vectorStore.relations(workspaceRoot.toString(), name);
            return SearchResultFormatter.formatGraph(name, relations);
        }
    }

    public String webSearch(String query, int topK) throws Exception {
        requireWebEnabled();
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return provider.unavailableHint();
        }
        List<WebSearchResult> results = provider.search(query, topK);
        return WebSearchFormatter.format(query, provider.name(), results);
    }

    public String webFetch(String url, int maxChars) throws Exception {
        requireWebEnabled();
        return WebFetchFormatter.format(fetcher().fetch(url, maxChars));
    }

    public void saveMemory(String content) throws Exception {
        memoryManager.saveFact(content);
    }

    public List<MemoryEntry> searchMemory(String query, int limit) {
        return memoryManager.searchLongTerm(query, limit);
    }

    public RuntimeStatus status() {
        return new RuntimeStatus(
            mode,
            workspaceRoot,
            memoryEnabled,
            ragEnabled,
            webEnabled,
            webProviderName(),
            hitlHandler.isEnabled(),
            parallelEnabled,
            concurrencyConfig.maxParallelism(),
            concurrencyConfig.batchTimeout(),
            isRagIndexed(),
            toolRegistry.getToolNames()
        );
    }

    public void clearSession() {
        if (reactAgent != null) {
            reactAgent.clearHistory();
        }
        memoryManager.clearShortTerm();
        if (multiAgent != null) {
            multiAgent.clearHistories();
        }
        hitlHandler.clearApprovedAll();
    }

    @Override
    public void close() {
        closeMultiAgent();
        closeToolCallExecutor();
    }

    private String runReact(String input) throws Exception {
        if (memoryEnabled) {
            return memoryAwareAgent.run(input);
        }
        return reactAgent.run(input);
    }

    private String runPlan(String input) throws Exception {
        String context = buildMemoryContext(input);
        context = joinContext(context, buildRagContext(input));
        if (memoryEnabled) {
            memoryManager.rememberUserMessage(input);
        }
        String result = planAgent.run(input, context).toMarkdown();
        if (memoryEnabled) {
            memoryManager.rememberAssistantMessage(result);
        }
        return result;
    }

    private String runMulti(String input) throws Exception {
        return multiAgent.execute(input, buildRagContext(input), System.out).toMarkdown();
    }

    private void rebuildComponents() {
        closeMultiAgent();
        closeToolCallExecutor();
        toolRegistry = new GuardedHitlToolRegistry(
            hitlHandler,
            workspaceRoot,
            new CommandGuard(),
            concurrencyConfig
        );

        String additionalInstructions = buildAdditionalInstructions();
        if (ragEnabled) {
            RagToolRegistrar.registerSearchCode(
                toolRegistry,
                workspaceRoot,
                embeddingClient,
                ragDbFile
            );
        }
        if (webEnabled) {
            WebToolRegistrar.register(toolRegistry, webSearchConfig);
        }

        if (memoryEnabled) {
            memoryAwareAgent = new MemoryAwareAgent(
                chatClient,
                toolRegistry,
                memoryManager,
                additionalInstructions,
                toolCallBatchExecutor()
            );
            reactAgent = null;
        } else {
            MemoryToolRegistrar.unregister(toolRegistry);
            memoryAwareAgent = null;
            reactAgent = new Agent(chatClient, toolRegistry, additionalInstructions, toolCallBatchExecutor());
        }

        ExecutionPlanExecutor executor = parallelEnabled
            ? new ParallelPlanExecutor(toolRegistry, concurrencyConfig)
            : new PlanExecutor(toolRegistry);
        planAgent = new PlanAndExecuteAgent(
            new DeepSeekPlanner(chatClient, parallelEnabled ? PARALLEL_PLANNER_INSTRUCTIONS : ""),
            executor
        );
        int workerCount = parallelEnabled ? concurrencyConfig.maxParallelism() : 1;
        multiAgent = new AgentOrchestrator(
            chatClient,
            toolRegistry,
            memoryEnabled ? memoryManager : null,
            workerCount,
            MAX_REVIEW_RETRIES,
            additionalInstructions,
            concurrencyConfig.batchTimeout(),
            new DaemonThreadFactory("bruce-cli-worker")
        );
    }

    private String buildAdditionalInstructions() {
        String instructions = memoryEnabled ? MemoryToolRegistrar.AGENT_INSTRUCTIONS : "";
        if (ragEnabled) {
            instructions = joinContext(instructions, RagToolRegistrar.AGENT_INSTRUCTIONS);
        }
        if (webEnabled) {
            instructions = joinContext(instructions, WebToolRegistrar.AGENT_INSTRUCTIONS);
        }
        if (parallelEnabled) {
            instructions = joinContext(instructions, PARALLEL_AGENT_INSTRUCTIONS);
        }
        return instructions;
    }

    private ToolCallExecutor toolCallBatchExecutor() {
        if (!parallelEnabled) {
            return ToolCallExecutor.serial(toolRegistry);
        }
        parallelToolCallExecutor = new ParallelToolCallExecutor(toolRegistry, concurrencyConfig);
        return parallelToolCallExecutor;
    }

    private String buildMemoryContext(String input) throws Exception {
        if (!memoryEnabled) {
            return "";
        }
        MemoryContext context = memoryManager.buildContext(input);
        return context.prompt();
    }

    private String buildRagContext(String input) throws Exception {
        if (!ragEnabled || !isRagIndexed()) {
            return "";
        }
        try (VectorStore vectorStore = new VectorStore(ragDbFile)) {
            CodeRetriever retriever = new CodeRetriever(
                workspaceRoot.toString(),
                embeddingClient,
                vectorStore
            );
            List<SearchResult> results = retriever.hybridSearch(input, 5);
            if (results.isEmpty()) {
                return "";
            }
            return SearchResultFormatter.formatForTool(input, results);
        }
    }

    private boolean isRagIndexed() {
        try (VectorStore vectorStore = new VectorStore(ragDbFile)) {
            return vectorStore.hasProject(workspaceRoot.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requireRagEnabled() {
        if (!ragEnabled) {
            throw new IllegalStateException("RAG 当前关闭，请先执行 /rag on");
        }
    }

    private void requireWebEnabled() {
        if (!webEnabled) {
            throw new IllegalStateException("Web 当前关闭，请先执行 /web on");
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (webSearchProvider == null) {
            webSearchProvider = SearchProviderFactory.create(webSearchConfig);
        }
        return webSearchProvider;
    }

    private synchronized WebFetcher fetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private String webProviderName() {
        if (!webEnabled) {
            return "disabled";
        }
        return searchProvider().name();
    }

    private String joinContext(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    private void closeMultiAgent() {
        if (multiAgent != null) {
            multiAgent.close();
            multiAgent = null;
        }
    }

    private void closeToolCallExecutor() {
        if (parallelToolCallExecutor != null) {
            parallelToolCallExecutor.close();
            parallelToolCallExecutor = null;
        }
    }
}
