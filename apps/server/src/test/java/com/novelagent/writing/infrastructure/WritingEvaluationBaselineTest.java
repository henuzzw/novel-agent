package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritingEvaluationBaselineTest {
    @TempDir Path temp;

    @Test
    void corpusHasBalancedPairsAcrossFourGenresAndAllDimensions() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        assertThat(corpus.version()).isEqualTo("writing-corpus/1");
        assertThat(corpus.contexts()).hasSize(4);
        assertThat(corpus.scenes()).hasSize(26);
        assertThat(corpus.scenes().stream().map(WritingEvaluationFixtures.Scene::id)).doesNotHaveDuplicates();
        var pairs = corpus.scenes().stream().collect(Collectors.groupingBy(WritingEvaluationFixtures.Scene::pairId));
        assertThat(pairs).hasSize(13);
        for (var pair : pairs.values()) {
            assertThat(pair).hasSize(2);
            assertThat(pair.stream().map(s -> s.expected().kind())).containsExactlyInAnyOrder("ISSUE", "CONTROL");
            assertThat(pair.stream().map(WritingEvaluationFixtures.Scene::contextId).distinct()).hasSize(1);
            assertThat(pair.stream().map(s -> s.expected().dimension()).distinct()).hasSize(1);
            assertThat(pair.stream().map(WritingEvaluationFixtures.Scene::objective).distinct()).hasSize(1);
        }
        assertThat(corpus.scenes().stream().map(s -> s.expected().dimension()).distinct())
                .containsExactlyInAnyOrder("STYLE", "FLUENCY", "LOGIC", "SCENE");
        for (var scene : corpus.scenes()) {
            assertThat(scene.body()).contains(scene.expected().evidence());
            assertThat(scene.expected().reason()).isNotBlank();
            assertThat(scene.expected().forbiddenEdits()).isNotEmpty();
            assertThat(scene.beats()).isNotEmpty();
            assertThat(corpus.context(scene.contextId()).bible().hardConstraints()).isNotEmpty();
        }
        for (var context : corpus.contexts()) {
            assertThat(corpus.scenes().stream().filter(s -> s.contextId().equals(context.id())))
                    .hasSize(context.id().equals("campus") ? 8 : 6);
            assertThat(context.profiles()).isNotBlank();
            assertThat(context.style().name()).isNotBlank();
        }
    }

    @Test
    void trilogiesHaveWholeShortChaptersAndHumanReaderRubrics() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        assertThat(corpus.trilogies()).hasSize(3);
        assertThat(corpus.trilogies().stream().map(WritingEvaluationFixtures.Trilogy::kind))
                .containsExactlyInAnyOrder("关系驱动", "悬疑信息驱动", "强情节行动驱动");
        var ids = new HashSet<String>();
        for (var trilogy : corpus.trilogies()) {
            assertThat(trilogy.readerPromise()).isNotBlank();
            assertThat(trilogy.rubric()).hasSizeGreaterThanOrEqualTo(4);
            assertThat(trilogy.chapters()).hasSize(3);
            for (var chapter : trilogy.chapters()) {
                assertThat(ids.add(chapter.id())).isTrue();
                assertThat(chapter.body().length()).isBetween(200, 600);
                assertThat(chapter.body()).contains("\n");
                assertThat(chapter.reveal()).isNotBlank();
                assertThat(chapter.exitState()).isNotBlank();
                assertThat(chapter.summary()).isNotBlank();
                assertThat(chapter.expected()).isNull();
            }
        }
    }

    @Test
    void captureUsesRealPromptsSchemasLimitsAndSessionPolicyWithoutLabelLeakage() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        var requests = WritingEvaluationFixtures.capture(corpus);
        assertThat(requests).hasSize(44);
        assertThat(requests.stream().map(WritingEvaluationFixtures.Request::id)).doesNotHaveDuplicates();
        assertThat(requests.stream().filter(r -> r.workflow().equals("MANUSCRIPT"))).hasSize(9);
        assertThat(requests.stream().filter(r -> r.workflow().equals("QUALITY_REVIEW"))).hasSize(35);
        for (var request : requests) {
            assertThat(request.codexSessionPolicy()).isEqualTo(CodexSessionPolicy.NEW_THREAD);
            assertThat(request.maxOutputTokens()).isEqualTo(5000);
            assertThat(request.schema().path("type").asText()).isEqualTo("object");
            assertThat(request.schema().path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(request.userPrompt()).contains(request.input().profiles(), request.input().styleGuide());
        }
        for (var scene : corpus.scenes()) {
            var request = requests.stream().filter(r -> r.id().equals(scene.id() + "-QUALITY_REVIEW")).findFirst().orElseThrow();
            assertThat(request.userPrompt()).contains(scene.body()).doesNotContain(scene.expected().reason(), scene.title());
            JsonNode snapshot = WritingEvaluationFixtures.MAPPER.valueToTree(request.input());
            for (String label : List.of("pairId", "expected", "forbiddenEdits")) {
                assertThat(snapshot.findValue(label)).isNull();
            }
        }
    }

    @Test
    void generationNeverSeesCurrentReferenceBodyAndKeepsEarlierChaptersUncommitted() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        var requests = WritingEvaluationFixtures.capture(corpus);
        for (var trilogy : corpus.trilogies()) {
            for (int i = 0; i < trilogy.chapters().size(); i++) {
                var scene = trilogy.chapters().get(i);
                var generation = requests.stream().filter(r -> r.id().equals(scene.id() + "-MANUSCRIPT")).findFirst().orElseThrow();
                assertThat(generation.input().manuscript()).isNull();
                assertThat(generation.userPrompt()).doesNotContain(scene.body());
                var memories = generation.input().memory().semanticMemories();
                assertThat(memories).hasSize(i);
                assertThat(memories).allMatch(m -> m.canonVersion() == 0 && m.chapterNumber() < generation.input().chapter().number());
                for (int j = 0; j < i; j++) {
                    assertThat(generation.userPrompt()).contains(trilogy.chapters().get(j).body());
                }
                if (i > 0) assertThat(generation.userPrompt()).contains("作者已确认，未提交正史");
            }
        }
    }

    @Test
    void frozenInputsAndPromptHashesAreRepeatableAndSensitiveToChanges() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        var first = WritingEvaluationFixtures.capture(corpus);
        var second = WritingEvaluationFixtures.capture(corpus);
        assertThat(first).isEqualTo(second);
        byte[] original = WritingEvaluationFixtures.MAPPER.writeValueAsBytes(first.getFirst());
        JsonNode modified = WritingEvaluationFixtures.MAPPER.valueToTree(first.getFirst());
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("userPrompt", "changed prompt");
        assertThat(WritingEvaluationFixtures.sha256(original)).hasSize(64)
                .isNotEqualTo(WritingEvaluationFixtures.sha256(WritingEvaluationFixtures.MAPPER.writeValueAsBytes(modified)));
    }

    @Test
    void exportHasVerifiableArtifactsAndDoesNotPretendToHaveModelResults() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        Path output = temp.resolve("baseline");
        WritingEvaluationFixtures.export(output, corpus, WritingEvaluationFixtures.capture(corpus));
        var mapper = WritingEvaluationFixtures.MAPPER;
        JsonNode manifest = mapper.readTree(output.resolve("manifest.json").toFile());
        assertThat(manifest.path("captureMode").asText()).isEqualTo("OFFLINE_NO_MODEL_CALLS");
        assertThat(manifest.path("supplierCalls").asInt(-1)).isZero();
        assertThat(manifest.path("corpusSha256").asText()).isEqualTo(WritingEvaluationFixtures.sha256(Files.readAllBytes(output.resolve("corpus.json"))));
        assertThat(manifest.path("requests")).hasSize(44);
        for (JsonNode entry : manifest.path("requests")) {
            byte[] bytes = Files.readAllBytes(output.resolve(entry.path("path").asText()));
            assertThat(WritingEvaluationFixtures.sha256(bytes)).isEqualTo(entry.path("requestSha256").asText());
            JsonNode request = mapper.readTree(bytes);
            assertThat(entry.path("inputSha256").asText()).isEqualTo(WritingEvaluationFixtures.sha256(mapper.writeValueAsBytes(request.path("input"))));
        }
        JsonNode results = mapper.readTree(output.resolve("results-template.json").toFile());
        assertThat(results).hasSize(44);
        for (JsonNode result : results) {
            assertThat(result.path("status").asText()).isEqualTo("NOT_RUN");
            for (String key : List.of("effectiveModel", "supplierCalls", "usage", "durationMs", "humanReview")) {
                assertThat(result.has(key)).isTrue();
                assertThat(result.path(key).isNull()).isTrue();
            }
        }
        assertThatThrownBy(() -> WritingEvaluationFixtures.export(output, corpus, List.of()))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void labelledEvidenceCanPassRealParserButFabricatedEvidenceCannot() throws Exception {
        var corpus = WritingEvaluationFixtures.load();
        var mapper = WritingEvaluationFixtures.MAPPER;
        var parser = new WritingModelOutputParser(mapper);
        for (var scene : corpus.scenes()) {
            var scores = List.of("STYLE", "FLUENCY", "LOGIC", "SCENE").stream()
                    .map(d -> Map.of("dimension", d, "score", 50, "rationale", "仅验证结构与证据，不代表模型评分")).toList();
            var issue = Map.of("id", "fixture", "category", scene.expected().dimension(), "severity", "WARNING",
                    "evidence", scene.expected().evidence(), "description", "合成证据校验用例",
                    "suggestion", "保留事实", "resolved", false);
            var report = mapper.valueToTree(Map.of("summary", "合成报告，不是检查器命中结果", "scores", scores, "issues", List.of(issue)));
            var manuscript = new com.novelagent.writing.domain.ManuscriptContent("微型场景", scene.body(), "原文", List.of());
            assertThat(parser.qualityReview(mapper.writeValueAsString(report), manuscript).issues()).hasSize(1);
            ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("issues").get(0)).put("evidence", "正文中不存在的伪造证据");
            assertThatThrownBy(() -> parser.qualityReview(mapper.writeValueAsString(report), manuscript))
                    .isInstanceOf(com.novelagent.planning.application.ModelProviderException.class);
        }
    }

    @Test
    void exportBaselineWhenExplicitlyRequested() throws Exception {
        String output = System.getProperty("novel.evaluation.output");
        Assumptions.assumeTrue(output != null && !output.isBlank(), "Baseline export is opt-in; no model calls are made.");
        var corpus = WritingEvaluationFixtures.load();
        WritingEvaluationFixtures.export(Path.of(output), corpus, WritingEvaluationFixtures.capture(corpus));
    }
}
