# 提示词文件

更新：2026-10-22。提示词只从文件读取，**在线“提示词管理”已下架**（页面、`/api/v1/settings/prompts` 接口和数据库覆盖都不再使用）。需要调整时直接编辑文件。

## 文件位置

所有提示词都在 `apps/server/src/main/resources/prompts/`，UTF-8 纯文本，文件名是模板键的小写形式：

| 文件 | 阶段 |
| --- | --- |
| `conversation_system.txt` | 默认系统角色（大多数阶段共用） |
| `snowflake_system.txt` / `project_chat_system.txt` | 雪花规划、项目对话的专属系统角色 |
| `book_title.txt` | 自动书名 |
| `project_chat.txt` | 项目创作对话 |
| `outline.txt` / `snowflake_planning.txt` / `character_design.txt` / `planning_checkpoint.txt` | 规划：分层大纲、雪花规划、人物设计、分块规划 |
| `manuscript.txt` / `manuscript_local_edit.txt` | 正文创作与润色、局部编辑 |
| `chapter_review.txt` | 发布后记忆整理 |
| `quality_review.txt` / `draft_judge_revision.txt` / `first_three_chapters_review.txt` | 文字质量检查、自动裁决与修订（C）、前三章连读 |
| `style_analysis.txt` / `style_recommendation.txt` / `style_preview.txt` / `style_preview_review.txt` / `style_preview_revision.txt` | 风格相关 |
| `import_source_analysis.txt` / `import_reverse_outline_adapt.txt` / `import_reverse_outline_continue.txt` | 导入：原文解析、导入大纲（改编 / 续写） |

阶段目录见 `AgentPromptCatalog`，文件加载见 `AgentPromptDefaults`。

## 怎么改

1. 编辑对应 `.txt` 文件。文件不能为空；不是变量模板，不执行表达式。
2. 重新构建并部署后端（提示词打包在 jar 里，启动后缓存）。
3. 已发出的请求不受影响；任务详情仍保存每次实际发送的系统和用户消息。

## 运行时不变的部分

- 每次调用的实际消息仍由服务端组装：系统角色 + 固定业务边界（`AgentPromptService.protectedRules`），用户消息 = 阶段提示词 + 输出协议 + 项目资料。固定边界写在代码里，改文件不能移除。
- 人物设计、大纲、分块规划会在文件内容后自动追加 `CharacterBlueprintGuide` 的人物规则。
- 模型继续返回纯文本，不恢复 JSON / JSON Schema。
- **会话轮转**：会话版本号由提示词文件内容的摘要生成。规划阶段共用一个会话，任一规划文件改动后下次调用自动开新会话；正文等独立阶段只看自己的文件。

## 历史数据

旧的 `user_agent_prompt` / `user_agent_prompt_revision` 表（V049、V055）保留但不再读写，作者此前在线保存的自定义内容不再生效。如需沿用，把内容合并进对应文件即可。

## 验证

```sh
cd apps/server
./mvnw -Dtest=AgentPromptServiceTest,AgentPromptDefaultsTest,StructuredModelGatewayTest test
```
