package com.novelagent.canon.application;

import com.novelagent.platform.support.Text;
import static com.novelagent.platform.support.Text.hasText;

import com.novelagent.canon.api.CharacterProfileResponse;
import com.novelagent.canon.api.UpdateCharacterProfileRequest;
import com.novelagent.canon.application.CharacterProfileStore.CharacterReference;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.domain.ChapterContractContent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人物档案。
 *
 * <p>管理独立设定与写作上下文中的人物档案筛选。精确引用不足时回退完整档案，不能用子串猜参与者；档案秘密不突破视角知识边界。</p>
 */
@Service
public class CharacterProfileService {
    private static final Pattern ENTITY_REFERENCE =
            Pattern.compile(
                    "\\{\\{entity:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):(CANONICAL|NICKNAME|TITLE)}}");
    private static final String KNOWLEDGE_BOUNDARY =
            "人物档案是作者侧设定参考，不代表视角人物已知信息；"
                    + "秘密、内在动机和人物弧光不得直接当作本章已发生事实或允许揭示的信息。"
                    + "叙述、对白与检查结论仍须遵守本章视角、正文证据及已提供的知识边界。\n";
    private final ProjectAccessService access;
    private final CharacterProfileStore store;

    public CharacterProfileService(ProjectAccessService access, CharacterProfileStore store) {
        this.access = access;
        this.store = store;
    }

    /**
     * 为当前有效人物幂等建立缺失的空档案后返回列表；这是带事务的补空操作，不会调用模型补齐详细设定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional
    public List<CharacterProfileResponse> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        store.ensureMissingProfiles(projectId);
        return store.profiles(projectId);
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    @Transactional
    public CharacterProfileResponse update(
            UUID projectId,
            UUID characterId,
            long expectedVersion,
            UpdateCharacterProfileRequest value) {
        access.requireOwnedProject(projectId);
        if (!store.containsActiveCharacter(projectId, characterId)) {
            throw new IllegalArgumentException("人物不存在");
        }
        store.ensureProfile(projectId, characterId);
        int changed = store.update(projectId, characterId, expectedVersion, value);
        if (changed != 1) {
            throw new ResourceVersionConflictException(
                    expectedVersion, requireProfile(projectId, characterId).version());
        }
        return requireProfile(projectId, characterId);
    }

    /**
     * 按明确人物 ID、角色键、姓名或别名选取有内容的档案；无法精确解析参与者时保守回退全量，不把秘密当作视角已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        List<CharacterProfileResponse> profiles =
                store.profiles(projectId).stream().filter(this::hasDetails).toList();
        return renderProfiles(profiles);
    }

    /**
     * 按明确人物 ID、角色键、姓名或别名选取有内容的档案；无法精确解析参与者时保守回退全量，不把秘密当作视角已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param pov 本章视角标识，不表示作者侧秘密对该人物可见。
     * @param relatedCharacterIds 本章明确关联的人物稳定 ID 集合。
     */
    @Transactional(readOnly = true)
    public String promptContext(UUID projectId, String pov, Collection<UUID> relatedCharacterIds) {
        return promptContext(projectId, pov, null, relatedCharacterIds);
    }

    /**
     * 按明确人物 ID、角色键、姓名或别名选取有内容的档案；无法精确解析参与者时保守回退全量，不把秘密当作视角已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param pov 本章视角标识，不表示作者侧秘密对该人物可见。
     * @param contract 本次关联章节合同，内容及版本必须与来源匹配。
     * @param relatedCharacterIds 本章明确关联的人物稳定 ID 集合。
     */
    @Transactional(readOnly = true)
    public String promptContext(
            UUID projectId,
            String pov,
            ChapterContractContent contract,
            Collection<UUID> relatedCharacterIds) {
        return promptContext(projectId, pov, contract, relatedCharacterIds, List.of());
    }

    /**
     * 按明确人物 ID、角色键、姓名或别名选取有内容的档案；无法精确解析参与者时保守回退全量，不把秘密当作视角已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param pov 本章视角标识，不表示作者侧秘密对该人物可见。
     * @param contract 本次关联章节合同，内容及版本必须与来源匹配。
     * @param relatedCharacterIds 本章明确关联的人物稳定 ID 集合。
     * @param relatedReferences 可核对的完整人物引用，模糊叙述不可用于子串猜测。
     */
    @Transactional(readOnly = true)
    public String promptContext(
            UUID projectId,
            String pov,
            ChapterContractContent contract,
            Collection<UUID> relatedCharacterIds,
            Collection<String> relatedReferences) {
        access.requireOwnedProject(projectId);
        Set<UUID> ids = new LinkedHashSet<>();
        if (relatedCharacterIds != null) {
            relatedCharacterIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        List<String> references = new ArrayList<>();
        references.add(pov);
        if (contract != null) {
            references.add(contract.pov());
            references.add(contract.objective());
            addReferences(references, contract.requiredBeats());
            addReferences(references, contract.requiredReveals());
            addReferences(references, contract.foreshadowActions());
            references.add(contract.expectedExitState());
            references.add(contract.hook());
        }
        addReferences(references, relatedReferences);
        references = references.stream().filter(Text::hasText).distinct().toList();
        if (references.isEmpty() && ids.isEmpty()) return "暂无本章相关人物档案";

        List<CharacterReference> characters = store.references(projectId);
        Set<UUID> activeIds =
                characters.stream().map(CharacterReference::id).collect(Collectors.toSet());
        boolean uncertain = !activeIds.containsAll(ids);
        for (String reference : references) {
            uncertain |= !resolveReference(reference, characters, ids);
        }
        if (uncertain) {
            // Free prose has no authoritative participant list; guessing a partial list can drop
            // key actors.
            return KNOWLEDGE_BOUNDARY
                    + "人物来源未能完全确定，回退到完整人物档案供核对；不表示所有人物都参与本章。\n"
                    + renderProfiles(
                            store.profiles(projectId).stream().filter(this::hasDetails).toList());
        }
        ids.retainAll(activeIds);
        if (ids.isEmpty()) return "暂无本章相关人物档案";
        var profiles = store.profiles(projectId, ids).stream().filter(this::hasDetails).toList();
        return profiles.isEmpty() ? "暂无本章相关人物档案" : KNOWLEDGE_BOUNDARY + renderProfiles(profiles);
    }

    private static void addReferences(List<String> target, Collection<String> references) {
        if (references != null) target.addAll(references);
    }

    /**
     * 仅接受完整分隔引用或稳定实体 ID；自由叙述及同名歧义返回未确定，触发全量档案回退而非子串猜测。
     *
     * @param reference 待核对的人物或来源引用，模糊引用不自动猜测。
     * @param characters 本次读取的人物命名快照，所有文本转换使用同一映射。
     * @param ids 作者显式选定的三章正文版本 ID。
     */
    private boolean resolveReference(
            String reference, List<CharacterReference> characters, Set<UUID> ids) {
        String remaining = reference.trim();
        var matcher = ENTITY_REFERENCE.matcher(remaining);
        boolean resolved = false;
        while (matcher.find()) {
            UUID id = UUID.fromString(matcher.group(1));
            if (characters.stream().noneMatch(character -> character.id().equals(id))) return false;
            ids.add(id);
            resolved = true;
        }
        remaining = matcher.replaceAll(" ").trim();
        // Only whole, delimited references are names. Unsegmented narrative requires the
        // conservative fallback.
        for (String part : remaining.split("[\\s,，、;；/()（）\\[\\]【】]+")) {
            if (part.isBlank()) continue;
            List<CharacterReference> matches =
                    characters.stream()
                            .filter(
                                    character ->
                                            part.equals(character.id().toString())
                                                    || part.equals(character.roleKey())
                                                    || character.names().contains(part))
                            .toList();
            if (matches.size() != 1) return false;
            ids.add(matches.getFirst().id());
            resolved = true;
        }
        return resolved;
    }

    private String renderProfiles(List<CharacterProfileResponse> profiles) {
        if (profiles.isEmpty()) return "暂无单独配置的人物档案";
        StringBuilder result = new StringBuilder();
        for (CharacterProfileResponse profile : profiles) {
            if (!result.isEmpty()) result.append('\n');
            result.append("人物：").append(profile.canonicalName());
            append(result, "性别", profile.gender());
            append(result, "年龄", profile.ageDescription());
            append(result, "身份", profile.identity());
            append(result, "外貌", profile.appearance());
            append(result, "背景", profile.background());
            append(result, "外在性格", profile.externalPersonality());
            append(result, "内在性格", profile.internalPersonality());
            append(result, "核心欲望", profile.coreDesire());
            append(result, "恐惧", profile.fear());
            append(result, "缺陷", profile.flaw());
            append(result, "价值观", profile.values());
            append(result, "说话方式", profile.speechStyle());
            append(result, "行为习惯", profile.behaviorHabits());
            append(result, "秘密", profile.secret());
            append(result, "人物弧光", profile.characterArc());
            append(result, "行为边界", profile.behaviorBoundaries());
            append(result, "补充备注", profile.notes());
        }
        return result.toString();
    }

    private boolean hasDetails(CharacterProfileResponse value) {
        return Arrays.asList(
                        value.gender(),
                        value.ageDescription(),
                        value.identity(),
                        value.appearance(),
                        value.background(),
                        value.externalPersonality(),
                        value.internalPersonality(),
                        value.coreDesire(),
                        value.fear(),
                        value.flaw(),
                        value.values(),
                        value.speechStyle(),
                        value.behaviorHabits(),
                        value.secret(),
                        value.characterArc(),
                        value.behaviorBoundaries(),
                        value.notes())
                .stream()
                .anyMatch(Text::hasText);
    }

    private CharacterProfileResponse requireProfile(UUID projectId, UUID characterId) {
        return store.find(projectId, characterId)
                .orElseThrow(() -> new IllegalArgumentException("人物档案不存在"));
    }

    private static void append(StringBuilder target, String label, String value) {
        if (hasText(value)) target.append("；").append(label).append("：").append(value.trim());
    }
}
