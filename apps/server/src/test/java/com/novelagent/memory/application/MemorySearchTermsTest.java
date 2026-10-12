package com.novelagent.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemorySearchTermsTest {
    @Test void prioritizesExplicitNamesAndFindsTermsInsideUnsegmentedChineseWithoutExtraModelCall() {
        var terms = MemorySearchTerms.keywords("把纸条交给班主任，然后核对双方的约定。", List.of("陆安影", "陈映畔"));
        assertThat(terms).startsWith("陆安影", "陈映畔").contains("纸条", "约定").doesNotHaveDuplicates();
        assertThat(MemorySearchTerms.keywords("超长中文查询".repeat(800), List.of())).hasSizeLessThanOrEqualTo(32);
    }
}
