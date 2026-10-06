package com.novelagent.planning.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CreationPreparation {
    private CreationPreparation() { }

    public record World(List<CharacterBlueprint> characters, List<Entity> entities) {
        public World {
            characters = list(characters, 12); entities = list(entities, 40);
            unique(characters.stream().map(CharacterBlueprint::name).toList());
            unique(entities.stream().map(Entity::key).toList());
        }
    }
    public record Entity(String key, String type, String name, String description, String initialState, String owner) {
        public Entity {
            key = CreationPreparation.key(key); name = text(name, 100, true); description = text(description, 3000, false);
            initialState = text(initialState, 2000, false); owner = text(owner, 100, false);
            if (!Set.of("ITEM", "LOCATION", "ORGANIZATION").contains(type == null ? "" : type)) invalid("实体类型无效");
        }
    }
    public record Unit(String key, String title, int startChapter, int endChapter, String objective,
            String conflict, String turningPoint, String endCondition, List<String> characters, List<String> planKeys) {
        public Unit {
            key = CreationPreparation.key(key); title = text(title, 200, true); objective = text(objective, 2000, true);
            conflict = text(conflict, 2000, true); turningPoint = text(turningPoint, 2000, false);
            endCondition = text(endCondition, 2000, true);
            characters = strings(characters, 12, 100); planKeys = strings(planKeys, 80, 80);
            if (startChapter < 1 || endChapter < startChapter) invalid("剧情单元章节范围无效");
        }
    }
    public record Relationship(String source, String target, String type, String description, int fromChapter) {
        public Relationship {
            source = text(source, 100, true); target = text(target, 100, true);
            type = text(type, 100, true); description = text(description, 2000, true);
            if (source.equals(target) || fromChapter < 1) invalid("规划关系无效");
        }
    }
    public record Knowledge(String character, String information, int knownFromChapter, String source) {
        public Knowledge {
            character = text(character, 100, true); information = text(information, 2000, true);
            source = text(source, 2000, true);
            if (knownFromChapter < 1) invalid("知识规划章节无效");
        }
    }
    public record Timeline(String key, int chapter, String storyTime, String event, List<String> participants) {
        public Timeline {
            key = CreationPreparation.key(key); storyTime = text(storyTime, 300, false); event = text(event, 2000, true);
            participants = strings(participants, 12, 100);
            if (chapter < 1) invalid("时间线章节无效");
        }
    }
    public record Plot(List<Unit> units, List<Relationship> relationships, List<Knowledge> knowledge,
            List<Timeline> timeline, List<ReaderExperienceSeed> readerExperiencePlans) {
        public Plot {
            units = list(units, 40); relationships = list(relationships, 80); knowledge = list(knowledge, 80);
            timeline = list(timeline, 120); readerExperiencePlans = StoryBibleContent.validatedPlans(readerExperiencePlans);
            unique(units.stream().map(Unit::key).toList()); unique(timeline.stream().map(Timeline::key).toList());
        }
        public void validate(World world, Set<Integer> chapters, int start, int end) {
            Set<String> names = new HashSet<>(world.characters().stream().map(CharacterBlueprint::name).toList());
            for (var entity : world.entities()) if (!entity.owner().isBlank()) named(names, entity.owner());
            int previous = start - 1;
            if (units.isEmpty()) invalid("至少需要一个剧情单元");
            Set<String> planKeys = new HashSet<>(readerExperiencePlans.stream().map(ReaderExperienceSeed::key).toList());
            for (var unit : units) {
                if (unit.startChapter() != previous + 1 || unit.endChapter() > end) invalid("剧情单元必须连续、无重叠地覆盖选定章节");
                for (int chapter = unit.startChapter(); chapter <= unit.endChapter(); chapter++) chapter(chapters, chapter, start, end);
                unit.characters().forEach(name -> named(names, name));
                if (!planKeys.containsAll(unit.planKeys())) invalid("剧情单元引用了不存在的台账 key");
                previous = unit.endChapter();
            }
            if (previous != end) invalid("剧情单元未覆盖选定范围");
            for (var relation : relationships) {
                named(names, relation.source()); named(names, relation.target()); chapter(chapters, relation.fromChapter(), start, end);
            }
            for (var item : knowledge) { named(names, item.character()); chapter(chapters, item.knownFromChapter(), start, end); }
            for (var item : timeline) { chapter(chapters, item.chapter(), start, end); item.participants().forEach(name -> named(names, name)); }
            for (var plan : readerExperiencePlans) if (plan.plannedChapter() != null) chapter(chapters, plan.plannedChapter(), start, end);
        }
    }
    public record Issue(String key, String severity, String category, String description, String sourceRef,
            String evidence, String suggestion) {
        public Issue {
            key = CreationPreparation.key(key); category = text(category, 80, true); description = text(description, 3000, true);
            sourceRef = text(sourceRef, 300, true); evidence = text(evidence, 3000, true);
            suggestion = text(suggestion, 3000, true);
            if (!Set.of("WARNING", "BLOCKING").contains(severity == null ? "" : severity)) invalid("复核问题级别无效");
        }
    }
    public record Adjustment(int chapterNumber, String objective, String coreEvent, String reveal,
            String endingHook, String reason) {
        public Adjustment {
            objective = text(objective, 3000, true); coreEvent = text(coreEvent, 3000, true);
            reveal = text(reveal, 3000, false); endingHook = text(endingHook, 3000, false);
            reason = text(reason, 3000, true);
            if (chapterNumber < 1) invalid("调整章节无效");
        }
    }
    public record PlanLink(String planId, String factId, String state, String evidence) {
        public PlanLink {
            java.util.UUID.fromString(planId); java.util.UUID.fromString(factId);
            evidence = text(evidence, 3000, true);
            if (!Set.of("SET_UP", "REINFORCED", "PAYOFF", "OPEN", "ABANDONED").contains(state)) invalid("台账关联状态无效");
        }
    }
    public record Review(String summary, List<Issue> issues, List<Adjustment> adjustments, List<PlanLink> planLinks) {
        public Review {
            summary = text(summary, 4000, true); issues = list(issues, 40); adjustments = list(adjustments, 40);
            planLinks = list(planLinks, 80); unique(issues.stream().map(Issue::key).toList());
            unique(adjustments.stream().map(value -> Integer.toString(value.chapterNumber())).toList());
        }
        public Review(String summary, List<Issue> issues, List<Adjustment> adjustments) {
            this(summary, issues, adjustments, List.of());
        }
        public void validate(JsonNode sources, Set<Integer> chapters, int lastCanonChapter) {
            for (var issue : issues) {
                JsonNode source;
                try { source = sources.at(issue.sourceRef()); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("复核来源必须是有效 JSON 路径", e); }
                if (!source.isTextual() || !source.textValue().contains(issue.evidence())) invalid("复核证据必须逐字存在于指定来源字段");
            }
            for (var adjustment : adjustments) {
                if (!chapters.contains(adjustment.chapterNumber()) || adjustment.chapterNumber() <= lastCanonChapter) {
                    invalid("只能建议调整最后正史章节之后的大纲章节");
                }
            }
        }
    }
    private static void chapter(Set<Integer> chapters, int value, int start, int end) {
        if (value < start || value > end || !chapters.contains(value)) invalid("规划引用了范围之外或不存在的章节");
    }
    private static void named(Set<String> names, String value) { if (!names.contains(value)) invalid("规划人物引用无法匹配：" + value); }
    private static String key(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,80}")) invalid("规划 key 无效");
        return value;
    }
    private static String text(String value, int max, boolean required) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || required && result.isEmpty()) invalid("规划字段为空或过长");
        return result;
    }
    private static <T> List<T> list(List<T> values, int max) {
        if (values == null || values.size() > max || values.stream().anyMatch(java.util.Objects::isNull)) invalid("规划列表缺失或超出上限");
        return List.copyOf(values);
    }
    private static List<String> strings(List<String> values, int max, int length) {
        return list(values, max).stream().map(value -> text(value, length, true)).toList();
    }
    private static void unique(List<String> keys) { if (new HashSet<>(keys).size() != keys.size()) invalid("规划 key 或人物重复"); }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
