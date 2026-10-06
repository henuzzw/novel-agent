package com.novelagent.writing.application;

import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleService;
import com.novelagent.writing.api.RecommendWritingStyleRequest;
import com.novelagent.writing.api.WritingStyleRecommendationResponse;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 圣经风格推荐。
 *
 * <p>根据指定圣经和当前数据库预设目录请求推荐，核对来源版本、内容及目录未变。推荐含可核验的圣经原文依据，不自动应用风格。</p>
 */
@Service
public class WritingStyleRecommendationService {
    private final StoryBibleService bibles;
    private final WritingGenerationWorkflow workflow;
    private final WritingStyleService styles;

    public WritingStyleRecommendationService(StoryBibleService bibles, WritingGenerationWorkflow workflow, WritingStyleService styles) {
        this.bibles = bibles;
        this.workflow = workflow;
        this.styles = styles;
    }

    /**
     * 根据指定圣经与数据库预设请求风格推荐，返回前核对圣经及预设目录未变；结果不自动采用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public WritingStyleRecommendationResponse recommend(UUID projectId, RecommendWritingStyleRequest request) {
        StoryBibleResponse source = bibles.version(projectId, request.bibleVersionId());
        WritingChecks.check(source.version(), request.expectedBibleVersion());
        var presets = styles.presets(projectId);
        WritingStyleRecommendationContent content = workflow.recommendStyle(projectId, source.content(),
                request.provider(), WritingChecks.normalize(request.instruction()));
        StoryBibleResponse current = bibles.version(projectId, request.bibleVersionId());
        WritingChecks.check(current.version(), source.version());
        if (!current.content().equals(source.content())) {
            throw new IllegalArgumentException("故事圣经的渲染内容已变化，请重新分析风格");
        }
        if (!presets.equals(styles.presets(projectId))) {
            throw new IllegalArgumentException("风格预设已变化，请重新推荐");
        }
        return new WritingStyleRecommendationResponse(source.id(), source.version(), source.generationNumber(),
                request.provider(), request.provider() == ModelProvider.LOCAL_TEMPLATE ? "TEMPLATE" : "MODEL",
                content.summary(), content.recommendations().stream().map(item ->
                        new WritingStyleRecommendationResponse.Recommendation(preset(presets, item.presetName()),
                                item.reason(), item.tradeoff(), item.evidence())).toList());
    }

    private WritingStyleProfile preset(java.util.List<WritingStyleProfile> presets, String name) {
        return presets.stream().filter(profile -> profile.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("推荐风格不是已知预设"));
    }
}
