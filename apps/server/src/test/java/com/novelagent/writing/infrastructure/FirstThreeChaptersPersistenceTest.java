package com.novelagent.writing.infrastructure;

import static com.novelagent.writing.FirstThreeChaptersFixtures.PROJECT;
import static com.novelagent.writing.FirstThreeChaptersFixtures.budget;
import static com.novelagent.writing.FirstThreeChaptersFixtures.contract;
import static com.novelagent.writing.FirstThreeChaptersFixtures.unassessed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.application.QualityReviewStore;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class FirstThreeChaptersPersistenceTest {
    final ProjectAccessService access = mock(ProjectAccessService.class);
    final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    final NovelProject project = mock(NovelProject.class);
    final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    final ManuscriptVersionRepository manuscripts = mock(ManuscriptVersionRepository.class);
    final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    final FirstThreeChaptersReportRepository reports = mock(FirstThreeChaptersReportRepository.class);
    final CharacterNameService names = mock(CharacterNameService.class);
    final CharacterProfileService profiles = mock(CharacterProfileService.class);
    final WritingStyleService styles = mock(WritingStyleService.class);
    final QualityReviewStore quality = mock(QualityReviewStore.class);
    final QualityReviewVersionRepository qualityReports = mock(QualityReviewVersionRepository.class);
    final EntityManager em = mock(EntityManager.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final OutlineVersion outline = mock(OutlineVersion.class);
    final StoryBibleVersion bible = mock(StoryBibleVersion.class);
    final UUID author = UUID.randomUUID(), outlineId = UUID.randomUUID(), bibleId = UUID.randomUUID();
    final List<ManuscriptVersion> texts = new ArrayList<>();
    final List<ChapterContractVersion> chapterContracts = new ArrayList<>();
    final FirstThreeChaptersPersistence store = new FirstThreeChaptersPersistence(access, actors, outlines, bibles,
            manuscripts, contracts, reports, names, profiles, styles, quality, em, new ObjectMapper(), jdbc, qualityReports);

    @BeforeEach void setup() {
        when(access.requireOwnedProject(PROJECT)).thenReturn(project);
        when(actors.currentUserId()).thenReturn(author);
        when(project.getCurrentOutlineVersionId()).thenReturn(outlineId);
        when(project.getCurrentBibleVersionId()).thenReturn(bibleId);
        when(outlines.findByIdAndProjectId(outlineId, PROJECT)).thenReturn(Optional.of(outline));
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getStatus()).thenReturn(OutlineStatus.PUBLISHED);
        when(outline.getSourceBibleVersionId()).thenReturn(bibleId);
        var plans = IntStream.rangeClosed(1, 3).mapToObj(n -> new ChapterPlan(n, "title", "pov", "objective", "event", "clue", "hook", 1000, 2000)).toList();
        when(outline.getContent()).thenReturn(new OutlineContent("title", "premise", "arc", "pacing", 3000, 6000,
                List.of(new OutlineArc(1, "title", "objective", "conflict", "turn", "payoff", 3000, 6000, plans))));
        when(bibles.findByIdAndProjectId(bibleId, PROJECT)).thenReturn(Optional.of(bible));
        when(bible.getId()).thenReturn(bibleId);
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(bible.getContent()).thenReturn(new StoryBibleContent("logline", "theme", "world", List.of(), "hero", "arc",
                List.of(), List.of(), "conflict", "stakes", "style", "ending", List.of(), List.of()));
        when(profiles.promptContext(PROJECT)).thenReturn("profile");
        when(styles.promptContext(PROJECT)).thenReturn("style");
        when(names.render(eq(PROJECT), anyString())).thenAnswer(a -> a.getArgument(1));
        when(names.render(eq(PROJECT), any(ManuscriptContent.class))).thenAnswer(a -> a.getArgument(1));
        when(names.render(eq(PROJECT), any(OutlineContent.class), eq(OutlineContent.class))).thenAnswer(a -> a.getArgument(1));
        when(names.render(eq(PROJECT), any(StoryBibleContent.class), eq(StoryBibleContent.class))).thenAnswer(a -> a.getArgument(1));
        when(names.render(eq(PROJECT), any(ChapterContractContent.class), eq(ChapterContractContent.class))).thenAnswer(a -> a.getArgument(1));
        when(reports.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
        for (int n = 1; n <= 3; n++) {
            var c = ChapterContractVersion.create(UUID.randomUUID(), PROJECT, outlineId, n, 1, "TEST", "", contract(n));
            var m = ManuscriptVersion.create(UUID.randomUUID(), PROJECT, c.getId(), n, 1, "TEST", "",
                    new ManuscriptContent("title " + n, "body " + n, "summary", List.of()));
            chapterContracts.add(c); texts.add(m);
            when(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, n)).thenReturn(List.of(m));
            when(manuscripts.findByIdAndProjectIdAndChapterNumber(m.getId(), PROJECT, n)).thenReturn(Optional.of(m));
            when(contracts.findByIdAndProjectIdAndChapterNumber(c.getId(), PROJECT, n)).thenReturn(Optional.of(c));
            when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, n)).thenReturn(Optional.of(c));
        }
    }
    @Test void ownerCheckPrecedesAllSourceAndReportReads() {
        when(access.requireOwnedProject(PROJECT)).thenThrow(new ProjectNotFoundException(PROJECT));
        assertThatThrownBy(() -> store.snapshot(PROJECT, List.of())).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> store.latest(PROJECT, "hash")).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(manuscripts, reports);
    }
    @Test void selectsSameProjectSameChapterHistoryAndCorrespondingContract() {
        var current = texts.getFirst();
        var old = ManuscriptVersion.create(UUID.randomUUID(), PROJECT, current.getSourceContractVersionId(), 1, 1, "TEST", "",
                new ManuscriptContent("old", "old complete body", "summary", List.of()));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(old.getId(), PROJECT, 1)).thenReturn(Optional.of(old));
        var source = store.snapshot(PROJECT, List.of(old.getId(), texts.get(1).getId(), texts.get(2).getId()));
        assertThat(source.available()).isTrue();
        assertThat(source.chapters().getFirst().body()).isEqualTo("old complete body");
        assertThat(source.chapters().getFirst().contractId()).isEqualTo(chapterContracts.getFirst().getId());
        var foreign = UUID.randomUUID();
        assertThatThrownBy(() -> store.snapshot(PROJECT, List.of(foreign, texts.get(1).getId(), texts.get(2).getId())))
                .isInstanceOf(WritingResourceNotFoundException.class);
    }
    @Test void missingChapterReturnsUnavailableAndNeverUsesContractAsBody() {
        when(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, 3)).thenReturn(List.of());
        var source = store.snapshot(PROJECT, List.of());
        assertThat(source.available()).isFalse();
        assertThat(source.chapters().getLast().body()).isNull();
        assertThat(source.unavailableReasons()).anyMatch(r -> r.contains("完整正文"));
    }
    @Test void olderOutlineOrSupersededContractMakesSourceUnavailable() {
        when(project.getCurrentOutlineVersionId()).thenReturn(UUID.randomUUID());
        assertThat(store.snapshot(PROJECT, List.of()).available()).isFalse();
        when(project.getCurrentOutlineVersionId()).thenReturn(outlineId);
        var next = ChapterContractVersion.create(UUID.randomUUID(), PROJECT, outlineId, 1, 2, "TEST", "", contract(1));
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, 1)).thenReturn(Optional.of(next));
        assertThat(store.snapshot(PROJECT, List.of()).unavailableReasons()).anyMatch(r -> r.contains("来源过期"));
    }
    @Test void draftOrNonCurrentBibleMakesSourceUnavailable() {
        when(bible.getStatus()).thenReturn(StoryBibleStatus.DRAFT);
        assertThat(store.snapshot(PROJECT, List.of()).unavailableReasons()).anyMatch(r -> r.contains("当前已发布版本"));
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(project.getCurrentBibleVersionId()).thenReturn(UUID.randomUUID());
        assertThat(store.snapshot(PROJECT, List.of()).available()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"body", "contract", "outline", "bible", "strategy", "style", "profile", "profileVersion", "canon", "newManuscript", "newContract"})
    void everyDependencyChangeRejectsReportBeforeSaving(String dependency) {
        var source = store.snapshot(PROJECT, List.of());
        switch (dependency) {
            case "body" -> texts.get(1).revise(new ManuscriptContent("title", "changed body", "summary", List.of()));
            case "contract" -> chapterContracts.get(1).revise(contract(99));
            case "outline" -> when(outline.getRowVersion()).thenReturn(1L);
            case "bible" -> when(bible.getRowVersion()).thenReturn(1L);
            case "strategy" -> when(project.getSetting(CreativeStrategyPolicy.SETTING_KEY))
                    .thenReturn(Map.of("strategy", "FANQIE_GRIPPING", "policyVersion", 1));
            case "style" -> when(styles.promptContext(PROJECT)).thenReturn("changed style");
            case "profile" -> when(profiles.promptContext(PROJECT)).thenReturn("changed profile");
            case "profileVersion" -> when(jdbc.queryForList(anyString(), eq(PROJECT))).thenReturn(List.of(Map.of("row_version", 1L)));
            case "canon" -> when(project.getCurrentCanonVersion()).thenReturn(1L);
            case "newManuscript" -> {
                var m = ManuscriptVersion.create(UUID.randomUUID(), PROJECT, chapterContracts.getFirst().getId(), 1, 2, "TEST", "",
                        new ManuscriptContent("new", "new complete body", "summary", List.of()));
                when(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, 1)).thenReturn(List.of(m, texts.getFirst()));
            }
            case "newContract" -> when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(PROJECT, 1))
                    .thenReturn(Optional.of(ChapterContractVersion.create(UUID.randomUUID(), PROJECT, outlineId, 1, 2, "TEST", "", contract(1))));
            default -> throw new AssertionError(dependency);
        }
        assertThatThrownBy(() -> store.save(source, List.of(), "DEEPSEEK", "", unassessed(), budget(true)))
                .hasMessageContaining("检查期间");
        verify(reports, never()).saveAndFlush(any());
        verify(em).refresh(project, LockModeType.PESSIMISTIC_WRITE);
    }
    @Test void reportIsSavedOnlyAfterShortLockedDependencyReadAndPrivateQueries() {
        var source = store.snapshot(PROJECT, List.of());
        var saved = store.save(source, List.of(), "DEEPSEEK", "", unassessed(), budget(true));
        assertThat(saved.getAuthorId()).isEqualTo(author);
        assertThat(saved.getSource().chapters().getLast().body()).isEqualTo("body 3");
        verify(em).clear();
        verify(em).refresh(outline, LockModeType.PESSIMISTIC_READ);
        verify(em).refresh(bible, LockModeType.PESSIMISTIC_READ);
        store.latest(PROJECT, source.fingerprint());
        verify(reports).findFirstByProjectIdAndAuthorIdAndFingerprintOrderByVersionNumberDesc(PROJECT, author, source.fingerprint());
    }
}
