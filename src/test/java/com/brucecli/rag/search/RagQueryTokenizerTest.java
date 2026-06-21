package com.brucecli.rag.search;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryTokenizerTest {
    @Test
    void keepsChineseTermsAndJavaIdentifiers() {
        Set<String> terms = new RagQueryTokenizer().tokenize("MemoryManager.run 方法在哪里实现?");

        assertTrue(terms.contains("memorymanager.run"));
        assertTrue(terms.contains("memorymanager"));
        assertTrue(terms.contains("run"));
        assertTrue(terms.contains("方法"));
    }
}
