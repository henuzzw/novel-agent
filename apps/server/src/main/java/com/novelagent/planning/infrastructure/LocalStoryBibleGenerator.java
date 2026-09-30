package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleGenerator;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalStoryBibleGenerator implements StoryBibleGenerator {
    @Override public ModelProvider provider() { return ModelProvider.LOCAL_TEMPLATE; }

    @Override
    public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, StoryBibleContent previousBible, String instruction) {
        if (previousBible != null) {
            List<String> questions = new ArrayList<>(previousBible.openQuestions());
            if (instruction != null && !instruction.isBlank()) questions.add("本版调整要求：" + instruction.trim());
            return new GeneratedStoryBible(provider().name(), new StoryBibleContent(
                    previousBible.logline(), previousBible.theme(), previousBible.worldSetting(),
                    previousBible.worldRules(), previousBible.protagonist(), previousBible.protagonistArc(),
                    previousBible.supportingCharacters(), previousBible.relationshipDynamics(),
                    previousBible.centralConflict(), previousBible.stakes(), previousBible.narrativeStyle(),
                    previousBible.endingDirection(), previousBible.hardConstraints(), List.copyOf(questions)),
                    List.of(instruction == null || instruction.isBlank()
                            ? "未发现需要修改的内容，沿用原版本。"
                            : "按照本次要求补充了待作者确认事项，其他故事圣经设定保持不变。"));
        }
        List<String> constraints = new ArrayList<>(intent.mustHave());
        intent.avoid().forEach(item -> constraints.add("不得出现：" + item));
        if (instruction != null && !instruction.isBlank()) constraints.add("本版调整：" + instruction.trim());
        StoryBibleContent content = new StoryBibleContent(
                direction.premise(),
                "人物只有在承担选择的代价后，才能真正改变自己与他人的关系。",
                value(intent.premise(), "故事发生在一个熟悉而暗含压力的现实环境中。"),
                List.of("关键行动必须产生可追踪的后果", "人物只能依据自己已经获得的信息行动", "重大转折必须有前置铺垫"),
                value(intent.protagonistBrief(), "主角拥有明确欲望，也带着阻碍其行动的内在缺口。"),
                direction.protagonistArc(),
                List.of("核心对手：追求与主角冲突的目标，但拥有可理解的理由", "关键同伴：既提供支持，也迫使主角面对自身弱点"),
                List.of("主角与核心对手从试探走向公开对抗", "主角与关键同伴的信任随每次选择发生变化"),
                direction.centralConflict(),
                "失败将同时造成外部目标落空、关键关系破裂和主角自我认同崩塌。",
                "采用贴近主角的有限视角，以具体行动和关系变化承载信息，保持" + String.join("、", intent.tones()) + "的基调。",
                direction.endingDirection(), constraints,
                List.of("核心对手最不愿承认的恐惧是什么？", "高潮前必须兑现哪一项关系承诺？"));
        return new GeneratedStoryBible(provider().name(), content);
    }

    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
}
