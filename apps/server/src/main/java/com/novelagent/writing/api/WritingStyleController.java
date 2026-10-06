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

    @GetMapping
    public WritingStyleService.StyleState get(@PathVariable UUID projectId) { return styles.get(projectId); }

    @GetMapping("/presets")
    public List<WritingStyleProfile> presets(@PathVariable UUID projectId) { return styles.presets(projectId); }

    @PutMapping
    public WritingStyleService.StyleState apply(@PathVariable UUID projectId, @RequestBody ApplyStyleRequest request) {
        return styles.apply(projectId, request.profile(), request.expectedVersion());
    }

    @PostMapping("/actions/analyze")
    public WritingStyleAnalysisService.Analysis analyze(@PathVariable UUID projectId, @RequestBody AnalyzeStyleRequest request) {
        return analysis.analyze(projectId, request.sampleText(), request.provider());
    }

    @PostMapping("/actions/preview")
    public WritingStylePreviewResponse preview(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateStylePreviewRequest request) {
        return previews.generate(projectId, request);
    }

    @PostMapping("/actions/recommend")
    public WritingStyleRecommendationResponse recommend(@PathVariable UUID projectId,
            @Valid @RequestBody RecommendWritingStyleRequest request) {
        return recommendations.recommend(projectId, request);
    }

    @PostMapping(value = "/actions/upload", consumes = "multipart/form-data")
    public WritingStyleAnalysisService.Analysis upload(@PathVariable UUID projectId, @RequestParam MultipartFile file,
            @RequestParam(required = false) ModelProvider provider) throws IOException {
        if (file.getSize() > 100000) throw new IllegalArgumentException("样本文件不能超过 100 KB");
        return analysis.upload(projectId, file.getOriginalFilename(), file.getBytes(), provider);
    }
}
