package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedStoryDirections;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryDirectionGenerator;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalStoryDirectionGenerator implements StoryDirectionGenerator {

    @Override
    public ModelProvider provider() {
        return ModelProvider.LOCAL_TEMPLATE;
    }

    @Override
    public GeneratedStoryDirections generate(
            UUID projectId,
            CreativeIntentSnapshot intent,
            List<StoryDirectionCandidate> previousDirections,
            String authorInstruction) {
        if (previousDirections != null && !previousDirections.isEmpty()) {
            List<StoryDirectionCandidate> revised = previousDirections.stream()
                    .map(candidate -> new StoryDirectionCandidate(candidate.id(), candidate.title(),
                            withAdjustment(candidate.premise(), authorInstruction), candidate.centralConflict(),
                            candidate.protagonistArc(), candidate.structure(), candidate.endingDirection(),
                            candidate.audienceFit(), candidate.strengths(), candidate.risks(),
                            candidate.distinctiveFeatures()))
                    .toList();
            return new GeneratedStoryDirections(provider().name(), revised, List.of(),
                    List.of("根据本次调整要求更新了三个候选方向的故事前提，其他核心设定保持不变。"));
        }
        String premise = sentenceStem(valueOr(intent.premise(), "主角被卷入一场会改变其人生的事件"));
        String conflict = sentenceStem(valueOr(intent.centralConflict(), "主角必须在个人愿望与现实代价之间做出选择"));
        String protagonist = sentenceStem(valueOr(intent.protagonistBrief(), "一个带着缺憾、仍在寻找答案的主角"));
        String audience = valueOr(intent.targetAudience(), "偏好人物成长与持续剧情推动的读者");
        String tone = intent.tones().isEmpty() ? "克制而有张力" : String.join("、", intent.tones());
        String ending = sentenceStem(valueOr(intent.endingPreference(), "完成核心人物弧光，同时保留余韵"));

        List<StoryDirectionCandidate> candidates = List.of(
                new StoryDirectionCandidate(
                        UUID.randomUUID(),
                        "关系裂变与自我重建",
                        withAdjustment(premise + "。故事把压力集中在主角最重要的关系上。", authorInstruction),
                        conflict + "，每次推进都会改变主角与关键人物之间的信任。",
                        protagonist + "，并从回避真实需求，成长为能够承担选择所带来关系代价的人。",
                        "以关系升级为骨架，按建立信任、产生裂痕、真相对峙、重新选择四个阶段推进。",
                        ending + "，最终决定落在一段关键关系是否值得重建。",
                        audience,
                        List.of("人物情感连续性强", "核心冲突容易贯穿长篇", "适合形成高记忆点对手戏"),
                        List.of("需要避免反复误会拖延", "配角动机必须充分"),
                        List.of("关系变化直接驱动情节", tone + "的近距离人物叙事")),
                new StoryDirectionCandidate(
                        UUID.randomUUID(),
                        "秘密调查与层层反转",
                        withAdjustment(premise + "。主角从一个异常细节出发，逐步触及被共同维护的秘密。", authorInstruction),
                        conflict + "，外部调查越接近真相，主角对自身经历的判断越不可靠。",
                        protagonist + "，并从依赖表面证据，成长为能够辨认立场、谎言与自身信念的人。",
                        "采用线索链结构，每一阶段解决一个局部谜题，同时推翻一个既有判断。",
                        ending + "，最后的揭示解释前文证据，但不依赖突然出现的新信息。",
                        audience,
                        List.of("情节牵引力强", "便于设计章节钩子", "可自然承载伏笔网络"),
                        List.of("反转必须有可回溯证据", "不能让人物只为谜题服务"),
                        List.of("证据与人物认知双线推进", "真相改变人物关系而不只是解谜")),
                new StoryDirectionCandidate(
                        UUID.randomUUID(),
                        "群像抉择与共同代价",
                        withAdjustment(premise + "。事件影响的不只是主角，而是一群目标互不相同的人。", authorInstruction),
                        conflict + "，每个人的自保选择会把共同困境推向更难收拾的局面。",
                        protagonist + "，并从独自承担或旁观，成长为能够组织行动并接受不完美结果的人。",
                        "以群体目标为主轴，交替推进个人支线，在中点形成联盟，在高潮前因代价分裂。",
                        ending + "，结局回应所有主要人物的选择，并留下可见的共同后果。",
                        audience,
                        List.of("世界和配角更立体", "适合中长篇扩展", "主题可以通过多种立场展开"),
                        List.of("需要控制人物数量", "必须保证主角仍是叙事重心"),
                        List.of("多立场冲突", "个人选择累积成集体后果")));

        List<String> questions = authorInstruction == null || authorInstruction.isBlank()
                ? List.of("你更看重人物关系、悬念推进，还是群像世界的展开？")
                : List.of();
        return new GeneratedStoryDirections(provider().name(), candidates, questions);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sentenceStem(String value) {
        return value.replaceFirst("[。！？；，.!?;,]+$", "");
    }

    private static String withAdjustment(String value, String authorInstruction) {
        if (authorInstruction == null || authorInstruction.isBlank()) {
            return value;
        }
        return value + " 本版额外侧重：" + sentenceStem(authorInstruction.trim()) + "。";
    }
}
