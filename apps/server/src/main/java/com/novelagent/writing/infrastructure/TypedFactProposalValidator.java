package com.novelagent.writing.infrastructure;

import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Set;
import java.util.UUID;

final class TypedFactProposalValidator {
    private static final Set<String> ENTITY_TYPES = Set.of(
            "CHARACTER", "LOCATION", "ORGANIZATION", "ITEM", "SECRET", "RULE");
    private static final Set<String> STATE_FIELDS = Set.of(
            "character.location", "character.physical_state", "character.emotional_state",
            "character.current_goal", "character.alive_status", "item.location", "item.holder",
            "item.condition");
    private static final Set<String> KNOWLEDGE_TYPES = Set.of("WITNESSED", "LEARNED", "BELIEVED", "SUSPECTED");
    private static final Set<String> BELIEF_VALUES = Set.of("TRUE", "FALSE", "UNVERIFIED");
    private static final Set<String> FORESHADOW_STATES = Set.of(
            "PLANNED", "PLANTED", "REINFORCED", "PARTIALLY_REVEALED", "RESOLVED", "ABANDONED");

    private TypedFactProposalValidator() {
    }

    static void validate(FactProposal fact) {
        if (fact == null || blank(fact.id()) || blank(fact.evidence())) {
            fail("候选事实缺少编号或正文证据");
        }
        if (fact.confidence() == null || fact.confidence() < 0 || fact.confidence() > 1) {
            fail("候选事实置信度必须在 0 到 1 之间");
        }
        TypedFactPayload payload = fact.payload();
        if (payload == null) {
            fail("候选事实缺少类型化 payload");
        }
        switch (fact.factType()) {
            case "ENTITY_UPSERT" -> {
                member(payload.entityType(), ENTITY_TYPES, "实体类型");
                required(first(payload.entityName(), fact.subject()), "实体名称");
                uuid(payload.entityId(), "实体 ID");
            }
            case "EVENT_CREATE" -> {
                required(first(payload.eventTitle(), fact.subject()), "事件标题");
                required(first(payload.eventSummary(), fact.object()), "事件摘要");
            }
            case "STATE_CHANGE" -> {
                member(payload.stateEntityType(), Set.of("CHARACTER", "ITEM"), "状态实体类型");
                required(first(payload.entityName(), fact.subject()), "状态实体名称");
                member(payload.fieldKey(), STATE_FIELDS, "状态字段");
                required(first(payload.afterValue(), fact.object()), "状态变化后的值");
                uuid(payload.entityId(), "状态实体 ID");
            }
            case "RELATION_CHANGE" -> {
                required(first(payload.sourceEntityName(), fact.subject()), "关系源实体");
                required(first(payload.targetEntityName(), fact.object()), "关系目标实体");
                required(first(payload.relationType(), fact.predicate()), "关系类型");
                uuid(payload.sourceEntityId(), "关系源实体 ID");
                uuid(payload.targetEntityId(), "关系目标实体 ID");
            }
            case "KNOWLEDGE_CHANGE" -> {
                required(first(payload.characterName(), fact.subject()), "认知人物");
                member(payload.knowledgeType(), KNOWLEDGE_TYPES, "知识类型");
                member(payload.beliefTruth(), BELIEF_VALUES, "信念真值");
                required(first(payload.statement(), fact.object()), "认知内容");
                uuid(payload.characterId(), "认知人物 ID");
            }
            case "FORESHADOW_CHANGE" -> {
                required(first(payload.foreshadowTitle(), fact.subject()), "伏笔标题");
                required(first(payload.targetEffect(), fact.object()), "伏笔目标效果");
                member(payload.foreshadowStatus(), FORESHADOW_STATES, "伏笔状态");
                if (payload.plannedResolveChapter() != null && payload.plannedResolveChapter() < 1) {
                    fail("伏笔计划回收章节必须是正整数");
                }
            }
            default -> fail("不支持的候选事实类型：" + fact.factType());
        }
    }

    private static void required(String value, String label) {
        if (blank(value)) fail(label + "不能为空");
    }

    private static void member(String value, Set<String> values, String label) {
        if (value == null || !values.contains(value)) fail(label + "不在允许范围内");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String first(String preferred, String fallback) {
        return blank(preferred) ? fallback : preferred;
    }

    private static void uuid(String value, String label) {
        if (blank(value)) return;
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            fail(label + "格式不正确");
        }
    }

    private static void fail(String message) {
        throw new ModelProviderException("模型返回的类型化候选事实无效：" + message);
    }
}
