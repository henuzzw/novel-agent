package com.novelagent.writing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.infrastructure.WritingStylePresetCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WritingStyleService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actors;
    private final ObjectMapper mapper;
    private final WritingStylePresetCatalog catalog;

    public WritingStyleService(NovelProjectRepository projects, CurrentActorProvider actors, ObjectMapper mapper,
            WritingStylePresetCatalog catalog) {
        this.projects = projects;
        this.actors = actors;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public record StyleState(WritingStyleProfile profile, long version) { }

    @Transactional(readOnly = true)
    public StyleState get(UUID projectId) {
        NovelProject project = requireOwnedProject(projectId);
        Object value = project.getSetting("writingStyle");
        return new StyleState(value == null ? null : catalog.resolve(mapper.convertValue(value, WritingStyleProfile.class)), project.getRowVersion());
    }

    @Transactional(readOnly = true)
    public List<WritingStyleProfile> presets(UUID projectId) {
        requireOwnedProject(projectId);
        return catalog.active();
    }

    @Transactional
    public StyleState apply(UUID projectId, WritingStyleProfile profile, long expectedVersion) {
        NovelProject project = requireOwnedProject(projectId);
        if (project.getRowVersion() != expectedVersion) throw new ResourceVersionConflictException(expectedVersion, project.getRowVersion());
        profile = catalog.resolve(profile);
        Map<String, Object> value = profile == null ? null : mapper.convertValue(profile, new TypeReference<Map<String, Object>>() { });
        project.setSetting("writingStyle", value);
        projects.saveAndFlush(project);
        return new StyleState(profile, project.getRowVersion());
    }

    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        WritingStyleProfile profile = get(projectId).profile();
        return profile == null ? "未指定；沿用故事圣经中的叙事风格。"
                : "风格只指导表达；章节合同的视角、人物身份、事实与硬约束优先。\n" + WritingStyleGuide.render(profile);
    }

    public WritingStyleProfile resolveProfile(WritingStyleProfile profile) {
        return catalog.resolve(profile);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
