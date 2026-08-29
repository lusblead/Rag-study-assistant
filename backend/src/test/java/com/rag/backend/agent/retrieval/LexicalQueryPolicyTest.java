package com.rag.backend.agent.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LexicalQueryPolicyTest {
    private final LexicalQueryPolicy policy = new LexicalQueryPolicy();

    @Test
    void normalChineseTextUsesNaturalLanguageMode() {
        LexicalQueryPolicy.QueryPlan plan = policy.plan("网关超时规则是什么");

        assertEquals(LexicalQueryPolicy.Mode.NATURAL_LANGUAGE, plan.mode());
        assertEquals("网关超时规则是什么", plan.boundQuery());
    }

    @Test
    void identifierAndNumberUseEscapedBooleanPhraseMode() {
        LexicalQueryPolicy.QueryPlan plan = policy.plan(
                "HTTP-404 @route \"user_id\"\\handler");

        assertEquals(LexicalQueryPolicy.Mode.BOOLEAN_PHRASE, plan.mode());
        assertEquals("\"HTTP 404 route user_id handler\"", plan.boundQuery());
        assertFalse(plan.boundQuery().substring(1,
                plan.boundQuery().length() - 1).matches(".*[+\\-<>\\(\\)~*@\\\"\\\\].*"));
    }

    @Test
    void blankQueryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy.plan("  "));
    }
}
