package com.novelagent.writing;

import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

public final class FirstThreeChaptersFixtures {
    private FirstThreeChaptersFixtures() { }
    public static final UUID PROJECT = UUID.randomUUID();
    public static FirstThreeChaptersSource source() {
        var chapters = IntStream.rangeClosed(1, 3).mapToObj(n -> new FirstThreeChaptersSource.Chapter(n,
                UUID.randomUUID(), 2, 7, "DRAFT", "Chapter " + n,
                "Opening " + n + ".\n" + "完整正文。".repeat(800) + "\nEnding " + n + ".",
                UUID.randomUUID(), 1, 3, "APPROVED", contract(n), List.of(), null, false)).toList();
        return new FirstThreeChaptersSource(PROJECT, UUID.randomUUID(), 4, UUID.randomUUID(), 5, 6,
                "FANQIE_GRIPPING/1", "complete outline", "complete bible", "style", "profiles", chapters, List.of(), "a".repeat(64));
    }
    public static ChapterContractContent contract(int n) {
        return new ChapterContractContent("Chapter " + n, "protagonist", "objective", "day", List.of("school"),
                List.of("act", "consequence"), List.of("clue"), List.of("future secret"), "exit", List.of("plant"), "hook", 1000, 2000);
    }
    public static FirstThreeChaptersContent unassessed() {
        return new FirstThreeChaptersContent("范围与限制", Arrays.stream(FirstThreeChaptersContent.Dimension.values())
                .map(d -> new FirstThreeChaptersContent.Assessment(d, FirstThreeChaptersContent.Status.NOT_ASSESSED,
                        "证据不足", List.of())).toList(), List.of());
    }
    public static FirstThreeChaptersBudget budget(boolean fits) {
        return new FirstThreeChaptersBudget(12000, 6000, 64000, 4000, 54000, fits, 1, "estimated");
    }
}
