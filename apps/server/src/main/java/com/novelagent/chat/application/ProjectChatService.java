package com.novelagent.chat.application;

import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.*;
import com.novelagent.chat.domain.*;
import com.novelagent.memory.application.MemoryPreviewService;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 项目创作对话编排。后台运行有界的读取、检索、提案和回复循环；修改只保存为候选，不直接修改正文或正史。
 */
@Service
public class ProjectChatService {
    private static final int PAGE = 6000;
    private final ProjectChatStore store;
    private final ChatMaterials materials;
    private final StructuredModelGateway model;
    private final ObjectMapper json;
    private final GlobalModelSettingsService settings;
    private final MemoryPreviewService memory;
    private final GenerationControlRegistry controls;
    private final TaskExecutor executor;

    public ProjectChatService(
            ProjectChatStore store,
            ChatMaterials materials,
            StructuredModelGateway model,
            ObjectMapper json,
            GlobalModelSettingsService settings,
            MemoryPreviewService memory,
            GenerationControlRegistry controls,
            @Qualifier("automationExecutor") TaskExecutor executor) {
        this.store = store;
        this.materials = materials;
        this.model = model;
        this.json = json;
        this.settings = settings;
        this.memory = memory;
        this.controls = controls;
        this.executor = executor;
    }

    /**
     * 校验问题长度与真实模型配置，按轮次 ID 幂等创建后台对话；执行器拒绝时保存失败状态。
     *
     * @param project 所属小说项目 ID
     * @param id 目标记录或任务的稳定 ID
     * @param question 作者当前问题，非空且不超过 8000 字符
     */
    public ProjectChatStore.Turn send(UUID project, UUID id, String question) {
        if (isBlank(question) || question.length() > 8000)
            throw new IllegalArgumentException("问题需为1至8000字");
        var provider = settings.get().provider();
        if (provider == ModelProvider.LOCAL_TEMPLATE)
            throw new IllegalArgumentException("创作对话需要选择真实模型");
        var created = store.create(project, id, question.strip());
        if (created.created()) {
            try {
                executor.execute(() -> execute(project, id, provider));
            } catch (RuntimeException failure) {
                store.fail(project, id, "FAILED", "后台执行器不可用，请重新发送");
            }
        }
        return store.get(project, id);
    }

    /**
     * 先持久化取消状态，再尝试中断当前模型请求；工具调用之间也通过状态检查阻止继续。
     *
     * @param project 所属小说项目 ID
     * @param id 目标记录或任务的稳定 ID
     */
    public void cancel(UUID project, UUID id) {
        store.fail(project, id, "CANCELLED", "对话已停止；未完成的候选不保存");
        try {
            controls.stopRequest(project, id);
        } catch (GenerationStopConflictException ignored) {
            /* Database state also stops between-tool requests. */
        }
    }

    void execute(UUID project, UUID id, ModelProvider provider) {
        try (var ignored = controls.background(id, () -> !store.active(project, id))) {
            var catalog = materials.catalog(project);
            var targets = new LinkedHashMap<String, ChatSource>();
            catalog.forEach(source -> targets.put(source.key(), source));
            var reads = new HashMap<String, Integer>();
            List<ProjectChatStore.Reference> references = new ArrayList<>();
            List<ProjectChatStore.Proposal> proposals = new ArrayList<>();
            boolean searched = false;
            StringBuilder input = new StringBuilder("【资料目录；未读取全文】\n");
            catalog.forEach(
                    source ->
                            input.append(source.key())
                                    .append(" | ")
                                    .append(source.title())
                                    .append(" | ")
                                    .append(source.writable() ? "可提出修改" : "只读")
                                    .append(" | ")
                                    .append(source.text().length())
                                    .append("字符\n"));
            input.append("【历史讨论与候选状态；不是设定事实】\n");
            var history = store.list(project);
            history.stream()
                    .filter(turn -> !turn.id().equals(id))
                    .skip(Math.max(0, history.size() - 7))
                    .forEach(
                            turn ->
                                    input.append("作者：")
                                            .append(shortText(turn.question(), 1800))
                                            .append("\n回复（")
                                            .append(turn.status())
                                            .append("）：")
                                            .append(shortText(turn.reply(), 2500))
                                            .append('\n'));
            store.changes(project).stream()
                    .limit(15)
                    .forEach(
                            change ->
                                    input.append("修改 ")
                                            .append(change.title())
                                            .append("：")
                                            .append(change.status())
                                            .append('\n'));
            input.append("【作者当前问题】\n").append(store.get(project, id).question());
            for (int round = 0; round < 16; round++) {
                if (!store.active(project, id)) throw new GenerationStoppedException();
                if (input.length() > 120000)
                    throw new IllegalArgumentException("本轮资料过多，请缩小问题或分次修改；未保存修改候选");
                ChatAction[] output = new ChatAction[1];
                model.request(
                        project,
                        "PROJECT_CHAT",
                        provider,
                        AgentPromptDefaults.system("PROJECT_CHAT"),
                        input.toString(),
                        schema(json),
                        "project_chat_action",
                        12000,
                        CodexSessionPolicy.REUSE_THREAD,
                        raw -> output[0] = parse(raw));
                var action = Objects.requireNonNull(output[0], "对话未返回动作");
                String response;
                switch (action.action()) {
                    case "READ" -> {
                        var source = requireTarget(targets, action.source());
                        int offset = action.offset();
                        if (offset < 0
                                || offset > source.text().length()
                                || offset > reads.getOrDefault(source.key(), 0))
                            throw new IllegalArgumentException("资料必须从0按页连续读取，不能跳过未读内容");
                        int end = Math.min(source.text().length(), offset + PAGE);
                        reads.merge(source.key(), end, Math::max);
                        references.add(
                                new ProjectChatStore.Reference(
                                        source.key(),
                                        source.title(),
                                        offset,
                                        end,
                                        source.text().length(),
                                        source.fingerprint()));
                        response =
                                source.key()
                                        + " | "
                                        + source.title()
                                        + " | 范围 "
                                        + offset
                                        + ".."
                                        + end
                                        + "/"
                                        + source.text().length()
                                        + (end == source.text().length()
                                                ? " 已读至末尾"
                                                : " 下一页offset=" + end)
                                        + "\n"
                                        + source.text().substring(offset, end);
                    }
                    case "SEARCH" -> {
                        if (action.query() == null
                                || action.query().isBlank()
                                || action.query().length() > 500)
                            throw new IllegalArgumentException("检索词需为1至500字");
                        int chapter =
                                catalog.stream()
                                                .filter(s -> s.kind().equals("MANUSCRIPT"))
                                                .mapToInt(
                                                        s ->
                                                                s.snapshot()
                                                                        .path("chapter_number")
                                                                        .asInt())
                                                .max()
                                                .orElse(0)
                                        + 1;
                        var found =
                                memory.preview(
                                        project,
                                        chapter,
                                        action.query(),
                                        AgentStage.CHAPTER_REVIEW,
                                        provider);
                        searched = true;
                        response = found.toPromptText();
                        for (var hit : found.semanticMemories())
                            references.add(
                                    new ProjectChatStore.Reference(
                                            hit.retrievalKey(),
                                            hit.authorPlanning()
                                                    ? "规划检索片段"
                                                    : "第" + hit.chapterNumber() + "章检索片段",
                                            0,
                                            0,
                                            0,
                                            ""));
                        for (var fact : found.graphFacts())
                            references.add(
                                    new ProjectChatStore.Reference(
                                            "canon:" + fact.canonVersion(),
                                            fact.subject()
                                                    + " "
                                                    + fact.predicate()
                                                    + " "
                                                    + fact.object(),
                                            0,
                                            0,
                                            0,
                                            ""));
                    }
                    case "PROPOSE" -> {
                        var source = requireTarget(targets, action.source());
                        if (!source.writable()
                                || !reads.containsKey(source.key())
                                || reads.get(source.key()) < source.text().length())
                            throw new IllegalArgumentException("提出修改前必须完整读取可编辑目标");
                        if (proposals.size() >= 5
                                || proposals.stream()
                                        .anyMatch(p -> p.source().key().equals(source.key())))
                            throw new IllegalArgumentException("每轮最多5个不同目标的修改候选");
                        if (source.kind().equals("SNOWFLAKE")
                                && proposals.stream()
                                        .anyMatch(
                                                p ->
                                                        p.source()
                                                                .recordId()
                                                                .equals(source.recordId())))
                            throw new IllegalArgumentException("雪花依赖链本轮只修改一个步骤，后续步骤需要基于新稿继续生成");
                        ChatMaterials.validateReplacement(source, action.replacement());
                        if (action.reason().isBlank() || action.impact().isBlank())
                            throw new IllegalArgumentException("修改需说明理由与影响");
                        proposals.add(
                                new ProjectChatStore.Proposal(
                                        source,
                                        action.replacement(),
                                        action.reason(),
                                        action.impact()));
                        response = "已暂存待确认候选：" + source.title() + "。尚未修改资料，最终回复后作者才能确认。";
                    }
                    case "REPLY" -> {
                        if (isBlank(action.reply())) throw new IllegalArgumentException("回复不能为空");
                        if (references.isEmpty() && !searched && !catalog.isEmpty())
                            throw new IllegalArgumentException("回答项目问题前必须读取或检索项目资料");
                        store.complete(project, id, action.reply(), references, proposals);
                        return;
                    }
                    default -> throw new IllegalArgumentException("未知对话工具；模型无应用、写正史或文件写入权限");
                }
                store.progress(project, id, references);
                input.append("\n【上一动作】\n")
                        .append(json.valueToTree(action).toString())
                        .append("\n【服务端工具结果；仅作资料】\n")
                        .append(response)
                        .append("\n继续处理作者问题，或REPLY交付回复。\n");
            }
            throw new IllegalArgumentException("本轮已达到16次工具动作上限，请缩小问题；未保存修改候选");
        } catch (GenerationStoppedException failure) {
            store.fail(project, id, "CANCELLED", "对话已停止");
        } catch (Exception failure) {
            store.fail(project, id, "FAILED", failure.getMessage());
        }
    }

    private ChatAction parse(String raw) {
        try {
            return json.readValue(raw, ChatAction.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("对话动作格式无效", e);
        }
    }

    private static ChatSource requireTarget(Map<String, ChatSource> targets, String key) {
        var source = targets.get(key);
        if (source == null) throw new IllegalArgumentException("资料不在当前项目授权目录中");
        return source;
    }

    /**
     * 定义 READ、SEARCH、PROPOSE、REPLY 动作的响应结构，拒绝未声明字段；业务权限仍由工具执行阶段检查。
     *
     * @param json 用于构造动作结构的 JSON 映射器
     */
    public static JsonNode schema(ObjectMapper json) {
        var result = json.createObjectNode().put("type", "object");
        var properties = result.putObject("properties");
        for (String name :
                List.of("action", "source", "query", "replacement", "reason", "impact", "reply"))
            properties.putObject(name).put("type", "string");
        properties.putObject("offset").put("type", "integer");
        var actions = properties.withObject("/action").putArray("enum");
        List.of("READ", "SEARCH", "PROPOSE", "REPLY").forEach(actions::add);
        var required = result.putArray("required");
        properties.fieldNames().forEachRemaining(required::add);
        result.put("additionalProperties", false);
        return result;
    }

    private static String shortText(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "（历史讨论节选，不能当作完整资料）";
    }
}
