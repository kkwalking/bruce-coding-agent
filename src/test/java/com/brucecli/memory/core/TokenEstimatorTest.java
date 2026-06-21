package com.brucecli.memory.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {
    @Test
    void estimatesChineseAndAsciiTokens() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertTrue(TokenEstimator.estimateTokens("我喜欢 JDK 17") >= 4);
        assertTrue(TokenEstimator.estimateTokens("hello world") >= 3);
    }
}
