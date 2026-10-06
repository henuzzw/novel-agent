package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class GenerationControlRegistryTest {
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); Thread.interrupted(); }

    @Test void requestAndRunReferToSameCallAndStopIsIdempotentAndProjectScoped() {
        var registry = new GenerationControlRegistry();
        UUID project = UUID.randomUUID(), run = UUID.randomUUID(), requestId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(GenerationControlRegistry.REQUEST_HEADER, requestId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (var call = registry.start(project, run)) {
            assertThatThrownBy(() -> registry.stopRequest(UUID.randomUUID(), requestId))
                    .isInstanceOf(GenerationStopConflictException.class);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            registry.stopRequest(project, requestId);
            registry.stopRun(project, run);
            assertThat(call.isStopped()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThatThrownBy(call::beginSaving).isInstanceOf(GenerationStoppedException.class);
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(() -> registry.stopRun(project, run)).isInstanceOf(GenerationStopConflictException.class);
        assertThatThrownBy(() -> registry.stopRequest(project, requestId)).isInstanceOf(GenerationStopConflictException.class);
    }

    @Test void acceptedOutputCannotBeCancelledDuringSaving() {
        var registry = new GenerationControlRegistry();
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        try (var call = registry.start(project, run)) {
            call.beginSaving();
            assertThatThrownBy(() -> registry.stopRun(project, run))
                    .isInstanceOf(GenerationStopConflictException.class).hasMessageContaining("保存结果");
            assertThat(call.isStopped()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    @Test void duplicateRequestDoesNotReplaceOriginalAndInvalidHeaderIsIgnored() {
        var registry = new GenerationControlRegistry();
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(GenerationControlRegistry.REQUEST_HEADER, UUID.randomUUID().toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (var call = registry.start(project, run)) {
            assertThatThrownBy(() -> registry.start(project, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
            registry.stopRun(project, run);
            assertThat(call.isStopped()).isTrue();
        }
        request.removeHeader(GenerationControlRegistry.REQUEST_HEADER);
        request.addHeader(GenerationControlRegistry.REQUEST_HEADER, "invalid");
        try (var call = registry.start(project, UUID.randomUUID())) { call.beginSaving(); }
    }
}
