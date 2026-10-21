package com.novelagent.writing.application;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Request-only selection. Versioned planning remains intact and global constraints are never top-K filtered. */
public final class WritingMaterialSelection {
    private WritingMaterialSelection() { }

    public static StoryBibleContent forChapter(StoryBibleContent source, String reference) {
        if (source == null) return null;
        String text = reference == null ? "" : reference;
        var characters = source.characterBlueprints();
        var participants = Pattern.compile("(?m)^\\s*(?:核心人物|出场人物)[：:]\\s*(.+)$").matcher(text);
        if (participants.find()) {
            var names = Arrays.stream(participants.group(1).split("[,，、;；\\s]+"))
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

    /** Opening reviews retain global promises and full character designs, not the redundant nine-step prose. */
    public static StoryBibleContent forOpening(StoryBibleContent source) {
        return source == null ? null : source.withDevelopmentNotes(null);
    }

    public static OutlineContent forOpening(OutlineContent source) {
        if (source == null) return null;
        var arcs = source.arcs().stream().map(arc -> arc.withChapters(arc.chapters().stream()
                        .filter(chapter -> chapter.number() >= 1 && chapter.number() <= 3).toList()))
                .filter(arc -> !arc.chapters().isEmpty()).toList();
        return new OutlineContent(source.title(), source.premise(), source.structureSummary(), source.pacingStrategy(),
                source.suggestedMinWords(), source.suggestedMaxWords(), arcs, source.readerExperiencePlans());
    }
}
