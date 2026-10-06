package com.novelagent.writing.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.writing.application.WritingStyleGuide;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Offline capture at the existing gateway boundary; no supplier or database is constructed.
final class WritingEvaluationFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String RESOURCE = "/evaluation/writing-corpus-v1.json";
    static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    record Corpus(String version, List<Context> contexts, List<Scene> scenes, List<Trilogy> trilogies) {
        Context context(String id) {
            return contexts.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        }
    }

    record Context(String id, String genre, WritingStyleProfile style, String pov, String storyTime,
            List<String> locations, String profiles, StoryBibleContent bible) {}

    record Expectation(String kind, String dimension, String evidence, String reason, List<String> forbiddenEdits) {}

    record Scene(String id, String pairId, String contextId, String title, String objective,
            List<String> beats, String reveal, String exitState, String hook, String summary,
            String body, Expectation expected) {}

    record Trilogy(String id, String contextId, String kind, String readerPromise,
            List<String> rubric, List<Scene> chapters) {}

    record InputSnapshot(StoryBibleContent bible, String profiles, WritingStyleProfile style,
            String styleGuide, OutlineArc arc, ChapterPlan chapter, ChapterContractContent contract,
            ManuscriptContent manuscript, NovelMemoryContext memory, String instruction) {}

    record Request(String id, String workflow, String systemPrompt, String userPrompt, JsonNode schema,
            String schemaName, int maxOutputTokens, CodexSessionPolicy codexSessionPolicy,
            InputSnapshot input) {}

    static byte[] corpusBytes() throws IOException {
        try (var stream = WritingEvaluationFixtures.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing evaluation corpus: " + RESOURCE);
            return stream.readAllBytes();
        }
    }

    static Corpus load() throws IOException {
        return MAPPER.readValue(corpusBytes(), Corpus.class);
    }

    static List<Request> capture(Corpus corpus) throws Exception {
        List<Request> requests = new ArrayList<>();
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        var model = mock(StructuredModelGateway.class);
        when(names.render(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var gateway = new WritingGenerationGateway(new WritingPromptFactory(MAPPER, names, profiles, styles, null),
                new WritingOutputSchemas(MAPPER), mock(WritingModelOutputParser.class),
                mock(LocalWritingGenerator.class), new WritingModelRouter(model),
                mock(LocalQualityReviewer.class), mock(LocalStyleAnalyzer.class));

        // Mutable capture state is confined to this single-threaded test invocation.
        var current = new ArrayList<InputSnapshot>(List.of());
        var currentId = new ArrayList<String>(List.of());
        when(model.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenAnswer(call -> {
                    requests.add(new Request(currentId.getFirst(), call.getArgument(1), call.getArgument(3),
                            call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7),
                            call.getArgument(8), current.getFirst()));
                    return "{}";
                });

        for (Scene scene : corpus.scenes()) {
            Context context = corpus.context(scene.contextId());
            ChapterPlan chapter = chapter(context, scene, 1, "微型场景");
            OutlineArc arc = arc(List.of(chapter));
            InputSnapshot input = input(context, scene, chapter, arc, memory(List.of(), "QUALITY_REVIEW"),
                    "这是独立微型场景，不按长篇小说整章长度判错；仅评估已展开场景及所给合同。", true);
            prepare(current, currentId, input, scene.id() + "-QUALITY_REVIEW", profiles, styles);
            gateway.qualityReview(PROJECT_ID, input.bible(), input.contract(), input.manuscript(), input.memory(),
                    ModelProvider.LOCAL_CODEX, input.instruction());
        }
        for (Trilogy trilogy : corpus.trilogies()) {
            Context context = corpus.context(trilogy.contextId());
            List<ChapterPlan> chapters = new ArrayList<>();
            for (int i = 0; i < trilogy.chapters().size(); i++) {
                Scene scene = trilogy.chapters().get(i);
                chapters.add(chapter(context, scene, i + 1, scene.title()));
            }
            OutlineArc arc = arc(chapters);
            List<NovelMemoryContext.SemanticMemory> previous = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                Scene scene = trilogy.chapters().get(i);
                ChapterPlan chapter = chapters.get(i);
                String instruction = "创作独立微型三章故事中的本章，建议 200～600 字；遵守本章合同，不提前兑现后章计划。";
                InputSnapshot generation = input(context, scene, chapter, arc, memory(previous, "MANUSCRIPT"), instruction, false);
                prepare(current, currentId, generation, scene.id() + "-MANUSCRIPT", profiles, styles);
                gateway.manuscript(PROJECT_ID, generation.bible(), arc, chapter, generation.contract(), generation.memory(),
                        null, ModelProvider.LOCAL_CODEX, instruction);

                InputSnapshot review = input(context, scene, chapter, arc, memory(previous, "QUALITY_REVIEW"),
                        "这是微型三章故事中的完整短章，不按常规长篇章节字数判错。", true);
                prepare(current, currentId, review, scene.id() + "-QUALITY_REVIEW", profiles, styles);
                gateway.qualityReview(PROJECT_ID, review.bible(), review.contract(), review.manuscript(), review.memory(),
                        ModelProvider.LOCAL_CODEX, review.instruction());

                String contract = MAPPER.writeValueAsString(review.contract());
                previous.addFirst(new NovelMemoryContext.SemanticMemory(i + 1, 0, 1, scene.summary(),
                        "已确认合同：" + contract + "\n作者已确认，未提交正史的正文：" + scene.body(), true,
                        contract, scene.body()));
            }
        }
        return List.copyOf(requests);
    }

    private static void prepare(List<InputSnapshot> current, List<String> ids, InputSnapshot input,
            String id, CharacterProfileService profiles, WritingStyleService styles) {
        current.clear();
        current.add(input);
        ids.clear();
        ids.add(id);
        when(profiles.promptContext(PROJECT_ID)).thenReturn(input.profiles());
        when(profiles.promptContext(eq(PROJECT_ID), any(), any(), any(), any())).thenReturn(input.profiles());
        when(styles.promptContext(PROJECT_ID)).thenReturn(input.styleGuide());
    }

    private static ChapterPlan chapter(Context context, Scene scene, int number, String title) {
        return new ChapterPlan(number, title, context.pov(), scene.objective(), String.join("；", scene.beats()),
                scene.reveal() == null ? "无额外揭示" : scene.reveal(), scene.hook(), 200, 600);
    }

    private static OutlineArc arc(List<ChapterPlan> chapters) {
        return new OutlineArc(1, "微型故事", "按章目标完成可观察变化", "遵守人物能力与信息边界",
                "以具体行动获得进展", "保留未解决问题及代价", 200 * chapters.size(), 600 * chapters.size(), chapters);
    }

    private static InputSnapshot input(Context context, Scene scene, ChapterPlan chapter, OutlineArc arc,
            NovelMemoryContext memory, String instruction, boolean includeManuscript) {
        var contract = new ChapterContractContent(chapter.title(), chapter.pov(), scene.objective(),
                context.storyTime(), context.locations(), scene.beats(),
                scene.reveal() == null ? List.of() : List.of(scene.reveal()), context.bible().hardConstraints(),
                scene.exitState(), List.of(), scene.hook(), chapter.suggestedMinWords(), chapter.suggestedMaxWords());
        var manuscript = includeManuscript ? new ManuscriptContent(chapter.title(), scene.body(),
                scene.summary() == null ? "微型场景，事实以正文为准" : scene.summary(), List.of()) : null;
        return new InputSnapshot(context.bible(), context.profiles(), context.style(), WritingStyleGuide.render(context.style()),
                arc, chapter, contract, manuscript, memory, instruction);
    }

    private static NovelMemoryContext memory(List<NovelMemoryContext.SemanticMemory> previous, String stage) {
        return new NovelMemoryContext(List.copyOf(previous), List.of(), new NovelMemoryContext.MemoryUsage(stage,
                "OFFLINE_FIXTURE", 32000, 0, 5000, 2000, 8000, 4000, 8000, 0, false, List.of()));
    }

    static void export(Path output, Corpus corpus, List<Request> requests) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output); // Never silently overwrite an earlier baseline.
        Files.createDirectory(output.resolve("requests"));
        Files.write(output.resolve("corpus.json"), corpusBytes());
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Request request : requests) {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(request);
            String path = "requests/" + request.id() + ".json";
            Files.write(output.resolve(path), bytes);
            entries.add(Map.of("requestId", request.id(), "path", path, "requestSha256", sha256(bytes),
                    "inputSha256", sha256(MAPPER.writeValueAsBytes(request.input())),
                    "promptSha256", sha256(MAPPER.writeValueAsBytes(List.of(request.systemPrompt(), request.userPrompt()))),
                    "schemaSha256", sha256(MAPPER.writeValueAsBytes(request.schema()))));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestId", request.id());
            result.put("status", "NOT_RUN");
            result.put("sampleIndex", 1);
            result.put("effectiveModel", null);
            result.put("startedAt", null);
            result.put("durationMs", null);
            result.put("supplierCalls", null);
            result.put("usage", null);
            result.put("usageKind", "UNKNOWN");
            result.put("rawOutputPath", null);
            result.put("failure", null);
            result.put("humanReview", null);
            results.add(result);
        }
        write(output.resolve("manifest.json"), Map.of("format", "writing-baseline/1", "corpusVersion", corpus.version(),
                "corpusSha256", sha256(corpusBytes()), "captureMode", "OFFLINE_NO_MODEL_CALLS",
                "supplierCalls", 0, "requests", entries));
        write(output.resolve("results-template.json"), results);
        write(output.resolve("labels.json"), Map.of("scenes", corpus.scenes().stream()
                .map(scene -> Map.of("id", scene.id(), "title", scene.title(), "pairId", scene.pairId(), "expected", scene.expected()))
                .toList(), "trilogies", corpus.trilogies().stream().map(trilogy -> Map.of("id", trilogy.id(),
                        "kind", trilogy.kind(), "readerPromise", trilogy.readerPromise(), "rubric", trilogy.rubric())).toList()));
    }

    private static void write(Path path, Object value) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private WritingEvaluationFixtures() {}
}
