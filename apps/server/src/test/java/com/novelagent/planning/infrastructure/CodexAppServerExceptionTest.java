package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodexAppServerExceptionTest {

    @Test
    void recognizesMissingRolloutAsMissingThread() {
        CodexAppServerException exception = new CodexAppServerException(
                "Codex thread/resume 失败：no rollout found for thread id 01a0eb6c-b8f8-7e33-9694-18ee4ef96bdc",
                -32600);

        assertThat(exception.indicatesMissingThread()).isTrue();
    }

    @Test
    void doesNotTreatUnrelatedProviderFailureAsMissingThread() {
        CodexAppServerException exception = new CodexAppServerException(
                "Codex thread/resume 失败：permission denied", -32600);

        assertThat(exception.indicatesMissingThread()).isFalse();
    }
}
