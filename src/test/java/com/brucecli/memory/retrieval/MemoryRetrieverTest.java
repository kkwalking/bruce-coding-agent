package com.brucecli.memory.retrieval;

import com.brucecli.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRetrieverTest {
    @Test
    void ranksRelevantFactFirst() {
        MemoryEntry javaFact = MemoryEntry.fact("用户偏好：Java 项目使用 JDK 17 和 Maven。");
        MemoryEntry frontendFact = MemoryEntry.fact("用户偏好：前端项目使用 React。");

        List<ScoredMemory> results = new MemoryRetriever().retrieve(
            List.of(frontendFact, javaFact),
            "JDK 17 Maven",
            2
        );

        assertFalse(results.isEmpty());
        assertEquals(javaFact.id(), results.get(0).entry().id());
    }

    @Test
    void usesJiebaSegmenterForChineseMemorySearch() {
        MemoryEntry projectFact = MemoryEntry.fact("项目默认使用 Maven 构建，后端代码用 Java 编写。");
        MemoryEntry styleFact = MemoryEntry.fact("用户喜欢简洁的 README 文档。");

        List<ScoredMemory> results = new MemoryRetriever().retrieve(
            List.of(styleFact, projectFact),
            "项目构建方式",
            2
        );

        assertFalse(results.isEmpty());
        assertEquals(projectFact.id(), results.get(0).entry().id());
    }

    @Test
    void jiebaWordSegmenterKeepsChineseAndEngineeringTerms() {
        Set<String> terms = new JiebaWordSegmenter().segment("用户喜欢 JDK 17，项目默认使用 Maven 构建。");

        assertTrue(terms.contains("用户"));
        assertTrue(terms.contains("项目"));
        assertTrue(terms.contains("jdk"));
        assertTrue(terms.contains("maven"));
    }
}
