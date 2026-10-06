package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.ChapterContractContent;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CharacterProfileServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CharacterProfileService service = new CharacterProfileService(projects, new CurrentActorProvider(owner), jdbc);
    private final List<String> queries = new ArrayList<>();
    private final List<UUID> selected = new ArrayList<>();
    private final Character lin = new Character(UUID.randomUUID(), "PROTAGONIST", "林安", "旧名", "小林", "林老师", List.of("阿林"), true);
    private final Character an = new Character(UUID.randomUUID(), "SUPPORTING", "安", null, "小安", null, List.of(), true);
    private final Character ning = new Character(UUID.randomUUID(), "RIVAL", "宁悦", null, "阿宁", null, List.of(), true);
    private List<Character> characters = List.of(lin, an, ning);

    @BeforeEach
    void owned() {
        when(projects.findById(project)).thenReturn(Optional.of(NovelProject.create(project, owner, "小说", EntryMode.MATERIALS)));
    }

    @Test
    void emptyRelevanceDoesNotLoadEveryProfile() {
        assertThat(service.promptContext(project, null, List.of())).isEqualTo("暂无本章相关人物档案");
        verifyNoInteractions(jdbc);
    }

    @Test
    void exactCurrentNameDoesNotSelectItsSubstring() throws Exception {
        database();
        assertThat(service.promptContext(project, "林安", List.of()))
                .contains("人物：林安", "不代表视角人物已知信息").doesNotContain("人物：安", "人物：宁悦", "回退");
        assertThat(selected).containsExactly(lin.id());
        assertThat(queries).allSatisfy(sql -> assertThat(sql).contains("e.project_id = ?", "e.canon_version_to IS NULL")
                .doesNotContain("position(", "LIKE"));
    }

    @Test
    void supportsCurrentNicknameTitleSourceAliasRoleAndUuidExactly() throws Exception {
        database();
        for (String reference : List.of("小林", "林老师", "旧名", "阿林", "PROTAGONIST", lin.id().toString(), token(lin))) {
            selected.clear();
            assertThat(service.promptContext(project, reference, List.of())).contains("人物：林安").doesNotContain("回退", "人物：安");
            assertThat(selected).containsExactly(lin.id());
        }
        assertThat(queries.getFirst()).contains("e.nickname", "e.title_name", "e.source_name",
                "a.project_id = e.project_id", "a.canon_version_to IS NULL");
    }

    @Test
    void combinesBothPovsContractActionsAndExplicitIdsWithoutDuplicates() throws Exception {
        database();
        var contract = new ChapterContractContent("约定", "阿宁", null, null, List.of(), List.of(token(lin)),
                List.of("小林"), List.of("禁止安出现"), null, List.of(token(ning)), null, 1000, 2000);
        String context = service.promptContext(project, "PROTAGONIST", contract, List.of(ning.id(), ning.id()));
        assertThat(context).contains("人物：林安", "人物：宁悦").doesNotContain("人物：安", "回退");
        assertThat(selected).containsExactly(ning.id(), lin.id());
        assertThat(context).contains("秘密、内在动机和人物弧光不得直接当作本章已发生事实", "正文证据");
    }

    @Test
    void naturalActionsFallbackInsteadOfDroppingTheOtherActorOrGuessingNames() throws Exception {
        database();
        var contract = new ChapterContractContent("约定", "林安", "安定下来", null, List.of(), List.of("林安递给阿宁纸条"),
                List.of(), List.of(), null, List.of(), null, 1000, 2000);
        assertThat(service.promptContext(project, "林安", contract, List.of()))
                .contains("回退到完整人物档案", "不表示所有人物都参与本章", "人物：宁悦", "人物：安");
        assertThat(selected).isEmpty();
        assertThat(queries).noneMatch(sql -> sql.contains("e.id IN"));
    }

    @Test
    void unresolvedOrAmbiguousSourcesFallbackEvenWithAKnownPovOrExplicitId() throws Exception {
        database();
        for (String reference : List.of("第三人称限知（林安）", "林安宁", "她", "{{entity:" + UUID.randomUUID() + ":CANONICAL}}")) {
            assertThat(service.promptContext(project, reference, List.of(lin.id())))
                    .contains("回退到完整人物档案", "人物：宁悦");
        }
        characters = List.of(lin, new Character(ning.id(), "RIVAL", "宁悦", null, "小林", null, List.of(), true));
        assertThat(service.promptContext(project, "小林", List.of())).contains("回退", "人物：林安", "人物：宁悦");
        assertThat(service.promptContext(project, "林安", List.of(UUID.randomUUID()))).contains("回退");
    }

    @Test
    void extraSourcesResolveWholeNamesButMixedFreeProseFallsBack() throws Exception {
        database();
        assertThat(service.promptContext(project, "林安", null, null, Arrays.asList(null, " ", "（阿宁）", token(lin))))
                .contains("人物：林安", "人物：宁悦").doesNotContain("人物：安", "回退");
        assertThat(selected).containsExactly(lin.id(), ning.id());
        assertThat(service.promptContext(project, token(lin), null, List.of(), List.of(token(lin) + "递给阿宁纸条")))
                .contains("回退", "人物：宁悦");
    }

    @Test
    void emptySelectedProfileDoesNotLoadUnrelatedDetailsAndLegacyRenderingStaysUnchanged() throws Exception {
        database();
        characters = List.of(new Character(lin.id(), lin.role(), lin.name(), null, null, null, List.of(), false), ning);
        assertThat(service.promptContext(project, "林安", List.of())).isEqualTo("暂无本章相关人物档案");
        assertThat(service.promptContext(project)).isEqualTo("人物：宁悦；身份：档案宁悦");
        characters = List.of();
        assertThat(service.promptContext(project)).isEqualTo("暂无单独配置的人物档案");
    }

    @Test
    void scopedContextRequiresAuthorPermission() {
        when(projects.findById(project)).thenReturn(Optional.of(NovelProject.create(project, UUID.randomUUID(), "他人小说", EntryMode.MATERIALS)));
        assertThatThrownBy(() -> service.promptContext(project, "林安", List.of())).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    private void database() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> mapper = invocation.getArgument(1);
            Object[] parameters = (Object[]) invocation.getRawArguments()[2];
            assertThat(parameters[0]).isEqualTo(project);
            queries.add(sql);
            List<Character> rows = characters;
            if (sql.contains("e.id IN")) {
                List<UUID> requested = Arrays.stream(parameters).skip(1).map(UUID.class::cast).toList();
                selected.addAll(requested);
                rows = characters.stream().filter(character -> requested.contains(character.id())).toList();
            }
            List<Object> result = new ArrayList<>();
            for (Character character : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(character.id());
                when(rs.getString("role_key")).thenReturn(character.role());
                when(rs.getString("canonical_name")).thenReturn(character.name());
                when(rs.getString("source_name")).thenReturn(character.source());
                when(rs.getString("nickname")).thenReturn(character.nickname());
                when(rs.getString("title_name")).thenReturn(character.title());
                when(rs.getString("identity_text")).thenReturn(character.details() ? "档案" + character.name() : null);
                java.sql.Array aliases = mock(java.sql.Array.class);
                when(aliases.getArray()).thenReturn(character.aliases().toArray(String[]::new));
                when(rs.getArray("aliases")).thenReturn(aliases);
                result.add(mapper.mapRow(rs, result.size()));
            }
            return result;
        });
    }

    private String token(Character character) {
        return "{{entity:" + character.id() + ":CANONICAL}}";
    }

    private record Character(UUID id, String role, String name, String source, String nickname,
            String title, List<String> aliases, boolean details) { }
}
