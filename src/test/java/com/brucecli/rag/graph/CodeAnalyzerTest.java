package com.brucecli.rag.graph;

import com.brucecli.rag.chunk.CodeChunkerTest;
import com.brucecli.rag.model.CodeRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsClassMethodAndCallRelationsFromJavaAst() throws Exception {
        Path javaFile = CodeChunkerTest.writeAgentSource(tempDir);

        List<CodeRelation> relations = new CodeAnalyzer(tempDir).analyzeFile(javaFile);

        assertTrue(relations.stream().anyMatch(relation ->
            relation.relationType().equals("imports")
                && relation.toName().equals("demo.tools.ToolRegistry")));
        assertTrue(hasRelation(relations, "extends", "Agent", "BaseAgent"));
        assertTrue(hasRelation(relations, "implements", "Agent", "Runnable"));
        assertTrue(hasRelation(relations, "contains", "Agent", "Agent.run"));
        assertTrue(hasRelation(relations, "calls", "Agent.run", "executeTool"));
    }

    private boolean hasRelation(List<CodeRelation> relations, String type, String from, String to) {
        return relations.stream().anyMatch(relation ->
            relation.relationType().equals(type)
                && relation.fromName().equals(from)
                && relation.toName().equals(to));
    }
}
