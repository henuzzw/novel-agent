package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.application.WritingStyleAnalysisService;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.application.WritingStylePreviewService;
import com.novelagent.writing.application.WritingStyleRecommendationService;
import com.novelagent.writing.domain.WritingStyleProfile;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 写作风格。
 *
 * <p>提供数据库预设、项目应用、样本分析、圣经推荐和第一章试写入口。推荐及试写只返回候选，只有显式应用才保存项目风格快照。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/writing-style")
public class WritingStyleController {
    private final WritingStyleService styles;
    private final WritingStyleAnalysisService analysis;
    private final WritingStylePreviewService previews;
    private final WritingStyleRecommendationService recommendations;

    public WritingStyleController(WritingStyleService styles, WritingStyleAnalysisService analysis,
            WritingStylePreviewService previews, WritingStyleRecommendationService recommendations) {
        this.styles = styles;
        this.analysis = analysis;
        this.previews = previews;
        this.recommendations = recommendations;
    }

    public record ApplyStyleRequest(WritingStyleProfile profile, long expectedVersion) { }
    public record AnalyzeStyleRequest(String sampleText, ModelProvider provider) { }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping
    public WritingStyleService.StyleState get(@PathVariable UUID projectId) { return styles.get(projectId); }

    /**
     * 读取数据库中启用的完整版本化风格目录；不按名称拼装或使用代码中的默认预设回退。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/presets")
    public List<WritingStyleProfile> presets(@PathVariable UUID projectId) { return styles.presets(projectId); }

    /**
     * 保存作者明确采用的风格快照或清除风格，按项目行版本保护；推荐和试写不会替代此应用动作。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping
    public WritingStyleService.StyleState apply(@PathVariable UUID projectId, @RequestBody ApplyStyleRequest request) {
        return styles.apply(projectId, request.profile(), request.expectedVersion());
    }

    /**
     * 对样本文字进行结构化风格分析，输出可编辑技法候选及原文依据，不移植样本人物、事件或自动应用项目。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/analyze")
    public WritingStyleAnalysisService.Analysis analyze(@PathVariable UUID projectId, @RequestBody AnalyzeStyleRequest request) {
        return analysis.analyze(projectId, request.sampleText(), request.provider());
    }

    /**
     * 按作者选定的大纲第一章与候选风格生成开头样例，不保存正式正文、不应用该风格。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/preview")
    public WritingStylePreviewResponse preview(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateStylePreviewRequest request) {
        return previews.generate(projectId, request);
    }

    /**
     * 根据指定圣经与数据库预设请求风格推荐，返回前核对圣经及预设目录未变；结果不自动采用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/recommend")
    public WritingStyleRecommendationResponse recommend(@PathVariable UUID projectId,
            @Valid @RequestBody RecommendWritingStyleRequest request) {
        return recommendations.recommend(projectId, request);
    }

    /**
     * 接收 UTF-8 TXT/Markdown 样本并进行风格分析，限制文件类型与大小；上传原文不成为本项目故事事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param file 上传的原始文件，仍须经过类型和大小检查。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     */
    @PostMapping(value = "/actions/upload", consumes = "multipart/form-data")
    public WritingStyleAnalysisService.Analysis upload(@PathVariable UUID projectId, @RequestParam MultipartFile file,
            @RequestParam(required = false) ModelProvider provider) throws IOException {
        if (file.getSize() > 100000) throw new IllegalArgumentException("样本文件不能超过 100 KB");
        return analysis.upload(projectId, file.getOriginalFilename(), file.getBytes(), provider);
    }
}
