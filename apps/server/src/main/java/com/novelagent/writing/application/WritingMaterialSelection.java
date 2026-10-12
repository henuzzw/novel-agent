package com.novelagent.writing.application;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;

/** Request-only selection. Versioned planning remains intact and global constraints are never top-K filtered. */
public final class WritingMaterialSelection {
    private WritingMaterialSelection() { }

    public static StoryBibleContent forChapter(StoryBibleContent source, String reference) {
        if (source == null) return null;
        String text = reference == null ? "" : reference;
        var characters = source.characterBlueprints();
        var participants = java.util.regex.Pattern.compile("(?m)^\\s*(?:核心人物|出场人物)[：:]\\s*(.+)$").matcher(text);
        if (participants.find()) {
            var names = java.util.Arrays.stream(participants.group(1).split("[,，、;；\\s]+"))
                    .filter(value -> !value.isBlank()).toList();
            // Only a complete, uniquely resolved roster permits exclusion. Aliases/free prose remain conservative.
            if (!names.isEmpty() && names.stream().allMatch(name -> source.characterBlueprints().stream()
                    .filter(person -> name.equals(person.name())).count() == 1)) {
                characters = source.characterBlueprints().stream()
                        .filter(person -> names.contains(person.name()) || "PROTAGONIST".equals(person.role())).toList();
            }
        }
        return new StoryBibleContent(source.logline(), source.theme(), source.worldSetting(), source.worldRules(),
                source.protagonist(), "", source.supportingCharacters(), source.relationshipDynamics(),
                source.centralConflict(), source.stakes(), source.narrativeStyle(), "", source.hardConstraints(),
                source.openQuestions(), characters, List.of(), null);
    }

    public static OutlineArc forChapter(OutlineArc arc, ChapterPlan chapter) {
        return new OutlineArc(arc.ordinal(), arc.title(), arc.objective(), arc.mainConflict(), "", "",
                arc.suggestedMinWords(), arc.suggestedMaxWords(), List.of(chapter));
    }
}
