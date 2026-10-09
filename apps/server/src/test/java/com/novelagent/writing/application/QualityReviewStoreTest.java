package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.QualityReviewVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QualityReviewStoreTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final NovelProject project = NovelProject.create(projectId, owner, "小说", EntryMode.MATERIALS);
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    private final ManuscriptVersionRepository manuscripts = mock(ManuscriptVersionRepository.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final CharacterProfileService profiles = mock(CharacterProfileService.class);
    private final WritingStyleService styles = mock(WritingStyleService.class);
    private final ManuscriptContent content = new ManuscriptContent("开头", "我合上书，等她说完。", "一次合作", List.of());
    private final OutlineVersion outline = mock(OutlineVersion.class);
    private final StoryBibleVersion bible = mock(StoryBibleVersion.class);
    private final ChapterContractVersion contract = mock(ChapterContractVersion.class);
    private final ManuscriptVersion manuscript = mock(ManuscriptVersion.class);
    private final QualityReviewStore store = new QualityReviewStore(outlines, bibles, contracts, manuscripts,
            mock(QualityReviewVersionRepository.class), new ProjectAccessService(projects, new CurrentActorProvider(owner)), names, profiles, styles, mock(EntityManager.class));

    @BeforeEach
    void setUp() {
        UUID outlineId = UUID.randomUUID();
        UUID bibleId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        project.publishOutline(outlineId);
        project.publishStoryBible(bibleId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).thenReturn(Optional.of(manuscript));
        when(manuscript.getChapterNumber()).thenReturn(1);
        when(manuscript.getProjectId()).thenReturn(projectId);
        when(manuscript.getSourceContractVersionId()).thenReturn(contractId);
        when(manuscript.getContent()).thenReturn(content);
        when(contract.getId()).thenReturn(contractId);
        when(contract.getChapterNumber()).thenReturn(1);
        when(contract.getSourceOutlineVersionId()).thenReturn(outlineId);
        when(contracts.findByIdAndProjectId(contractId, projectId)).thenReturn(Optional.of(contract));
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).thenReturn(Optional.of(contract));
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getStatus()).thenReturn(OutlineStatus.PUBLISHED);
        when(outline.getContent()).thenReturn(new com.novelagent.planning.domain.OutlineContent(
                "小说", "前提", "结构", "节奏", 1000, 2000, List.of(new com.novelagent.planning.domain.OutlineArc(
                        1, "幕", "目标", "冲突", "转折", "结果", 1000, 2000, List.of(new com.novelagent.planning.domain.ChapterPlan(
                                1, "章", "主角", "目标", "事件", "揭示", "钩子", 1000, 2000))))));
        when(outline.getSourceBibleVersionId()).thenReturn(bibleId);
        when(outlines.findByIdAndProjectId(outlineId, projectId)).thenReturn(Optional.of(outline));
        when(bible.getContent()).thenReturn(com.novelagent.planning.domain.CharacterBlueprintFixtures.bible(List.of()));
        when(bible.getId()).thenReturn(bibleId);
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(bibles.findByIdAndProjectId(bibleId, projectId)).thenReturn(Optional.of(bible));
        when(names.render(projectId, content)).thenReturn(content);
        when(names.render(any(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> call.getArgument(1));
        when(profiles.promptContext(projectId)).thenReturn("固定人物档案");
        when(styles.promptContext(projectId)).thenReturn("轻快口语");
    }

    @Test
    void sourceFingerprintTracksPolicyAndOutlineBibleVersions() {
        String first = store.snapshot(projectId, 1).fingerprint();
        CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING).applyTo(project);
        String changedStrategy = store.snapshot(projectId, 1).fingerprint();
        assertThat(changedStrategy).isNotEqualTo(first);
        when(outline.getRowVersion()).thenReturn(1L);
        String changedContract = store.snapshot(projectId, 1).fingerprint();
        assertThat(changedContract).isNotEqualTo(changedStrategy);
        when(bible.getRowVersion()).thenReturn(1L);
        assertThat(store.snapshot(projectId, 1).fingerprint()).isNotEqualTo(changedContract);
    }

    @Test
    void wrongChapterOrChangedCurrentOutlineStopsBeforeChecking() {
        when(manuscript.getChapterNumber()).thenReturn(2);
        assertThatThrownBy(() -> store.snapshot(projectId, 1)).isInstanceOf(IllegalArgumentException.class);
        when(manuscript.getChapterNumber()).thenReturn(1);
        project.publishOutline(UUID.randomUUID());
        assertThatThrownBy(() -> store.snapshot(projectId, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
