package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodexMaterialDeltaTest {
    @Test void keepsInstructionsAndReferencesOnlyExactRepeatedData() {
        String rules = "规则".repeat(5000);
        String source = "完整原文".repeat(400);
        String prompt = "【本轮服务端执行规范】\n" + rules + "\n【本轮创作资料】\n【原文】\n" + source;
        var first = CodexMaterialDelta.build(prompt, Set.of());
        assertThat(first.text()).contains(rules, source);
        var next = CodexMaterialDelta.build(prompt, first.materials().keySet());
        assertThat(next.text()).contains(rules, "此前提供过").doesNotContain(source);
        assertThat(next.materials()).containsValue(source);
        var changed = CodexMaterialDelta.build(prompt + "修改", first.materials().keySet());
        assertThat(changed.text()).contains(source + "修改");
    }
    @Test void storesLargeMaterialUnchangedAndMakesFirstReadExplicit() {
        String source = "逐字核对\n".repeat(3000);
        var delta = CodexMaterialDelta.build("【本轮创作资料】\n【完整素材】\n" + source, Set.of());
        assertThat(delta.text()).contains("首次提供").doesNotContain(source);
        assertThat(delta.materials()).containsValue(source);
        assertThat(delta.materials().keySet().iterator().next()).hasSize(64);
    }
}
