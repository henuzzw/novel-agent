package com.novelagent.modelaccess.infrastructure;

import com.novelagent.platform.support.Sha256;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodexMaterialReadToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void pagesRecoverExactTextAndDoNotSplitUnicode() {
        String source = "字".repeat(5999) + "\uD83D\uDE00" + "\r\n后续原文".repeat(400);
        String id = Sha256.ofUtf8(source);
        var params = json.createObjectNode().put("tool", CodexMaterialReadTool.NAME);
        var args = params.putObject("arguments").put("material_id", id).put("offset", 0);
        StringBuilder recovered = new StringBuilder();
        int offset = 0;
        while (offset < source.length()) {
            args.put("offset", offset);
            var result = CodexMaterialReadTool.respond(json, params, Set.of(id), ignored -> source);
            assertThat(result.path("success").asBoolean()).isTrue();
            String page = result.at("/contentItems/0/text").asText();
            String body = page.substring(page.indexOf('\n') + 1);
            recovered.append(body);
            offset += body.length();
            assertThat(page).contains("next_offset=" + (offset == source.length() ? "END" : offset));
        }
        assertThat(recovered.toString()).isEqualTo(source);
    }

    @Test void invalidArgumentsAndUnauthorizedIdsNeverAccessStorage() {
        var reads = new AtomicInteger();
        String id = "a".repeat(64);
        var params = json.createObjectNode().put("tool", CodexMaterialReadTool.NAME);
        var args = params.putObject("arguments").put("material_id", id).put("offset", 0);
        assertThat(CodexMaterialReadTool.respond(json, params, Set.of(), key -> { reads.incrementAndGet(); return "secret"; })
                .path("success").asBoolean()).isFalse();
        args.put("material_id", "../auth.json");
        assertThat(CodexMaterialReadTool.respond(json, params, Set.of("../auth.json"), key -> { reads.incrementAndGet(); return "secret"; })
                .path("success").asBoolean()).isFalse();
        args.put("material_id", id).put("offset", "0");
        assertThat(CodexMaterialReadTool.respond(json, params, Set.of(id), key -> { reads.incrementAndGet(); return "secret"; })
                .path("success").asBoolean()).isFalse();
        assertThat(reads.get()).isZero();
    }

    @Test void offsetsAndReaderFailuresReturnSafeErrors() {
        String id = "a".repeat(64);
        var params = json.createObjectNode().put("tool", CodexMaterialReadTool.NAME);
        var args = params.putObject("arguments").put("material_id", id);
        for (long offset : new long[] {-1, 6, Long.MAX_VALUE}) {
            args.put("offset", offset);
            assertThat(CodexMaterialReadTool.respond(json, params, Set.of(id), ignored -> "secret").path("success").asBoolean()).isFalse();
        }
        args.put("offset", 0);
        var result = CodexMaterialReadTool.respond(json, params, Set.of(id), ignored -> {
            throw new IllegalStateException("private file path and credentials");
        });
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain("credentials", "private file");
    }
}
