package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChapterPlanTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChapterPlan plan = new ChapterPlan(14, "被叫去谈话",
            "视角：周启\n字数要求：2500字\n行动：询问谈话的原因。\n场景：教室，B收回温柔。", ChapterPlanStatus.PLANNED);

    @Test void roundTripStoresOneBlockWithoutLiteraryFields() throws Exception {
        var json = mapper.valueToTree(plan);
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.has("pov") || json.has("sceneOutline") || json.has("suggestedMinWords")).isFalse();
        assertThat(mapper.treeToValue(json, ChapterPlan.class)).isEqualTo(plan);
        assertThat(plan.withStatus(ChapterPlanStatus.OCCURRED).content()).isEqualTo(plan.content());
    }

    @Test void oldProseIsJoinedWithoutLossButNotSerializedAsOldFields() throws Exception {
        var old = mapper.readTree("""
                {"number":14,"title":"被叫去谈话","pov":"周启","objective":"询问",
                "coreEvent":"谈话","reveal":"收回温柔","endingHook":"家长","status":"PLANNED",
                "suggestedMinWords":2500,"suggestedMaxWords":2500,"sceneOutline":"教室：主动询问。"}
                """);
        var value = mapper.treeToValue(old, ChapterPlan.class);
        assertThat(value.content()).contains("周启", "询问", "谈话", "收回温柔", "家长", "2500", "教室：主动询问。");
        assertThat(mapper.valueToTree(value).has("coreEvent")).isFalse();
    }

    @Test void upstreamChangesMarkReusedContentAndEditingClearsIt() {
        var stale = plan.reviewContentAgainst(plan, true);
        assertThat(stale.contentNeedsUpdate()).isTrue();
        assertThat(stale.reviewContentAgainst(stale, false).contentNeedsUpdate()).isTrue();
        var updated = new ChapterPlan(14, plan.title(), plan.content() + "\n按新依据调整行动。", plan.status());
        assertThat(updated.reviewContentAgainst(stale, true).contentNeedsUpdate()).isFalse();
        assertThat(updated.content()).startsWith(plan.content());
    }

    @Test void freeProseIsAcceptedButEmptyOrMisroutedPlansAreRejected() {
        assertThat(new ChapterPlan(1, "标题", "无需固定标签的具体行动规划。", ChapterPlanStatus.PLANNED).content()).isNotBlank();
        assertThatThrownBy(() -> new ChapterPlan(1, "标题", " ", ChapterPlanStatus.PLANNED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChapterPlan(0, "标题", "规划", ChapterPlanStatus.PLANNED)).isInstanceOf(IllegalArgumentException.class);
    }
}
