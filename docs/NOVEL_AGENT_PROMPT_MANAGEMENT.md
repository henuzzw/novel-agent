# 全局提示词管理

## 入口与范围

- 顶部“提示词管理”，或项目“设置 → 全局提示词”。
- 页面地址：`/settings/prompts`；`?template=MANUSCRIPT` 直接打开阶段，刷新保留选择。
- 当前用户全局生效，跨项目共用，不修改其他用户配置。
- 当前目录为 21 种模型工作流、23 份阶段模板，导入圣经和大纲分别区分改编、续写。
- 本地确定性模板不调用模型，不执行这些模型提示词。

## 配对编辑

接入区别：新 ChatGPT OAuth HTTP 路线每轮明确发送当前配对系统文本到 `instructions`；旧 Codex App Server 使用 `developerInstructions`，不改写 Codex 内置基础系统提示词，其已挂载线程的更新缺口尚未修复。详见 [直连接入与验证](NOVEL_AGENT_CHATGPT_DIRECT_ACCESS.md)。

每个阶段在同一界面编辑、保存一对提示词：

1. **系统提示词**：实际发送到模型系统角色的指令，每个阶段独立配置。默认值来自 `prompts/conversation_system.txt`。
2. **用户提示词**：当前阶段的角色、职责和创作要求，发送到本轮用户消息的“本轮服务端执行规范”。沿用此前可编辑阶段指令，默认值来自对应 `prompts/*.txt`。
3. **阶段执行规则**：可选补充规则，与用户提示词一起发送。

三个字段各不超过 40000 字符，系统和用户提示词不能为空。编辑框不是变量模板，不执行表达式或脚本。

项目圣经、大纲、正文、人物档案、记忆、选定风格、策略、版本基准等业务资料仍由系统自动组装，附在“本轮创作资料”后，不需要作者手工复制。固定事实、授权、证据、知识边界及候选权限由网关追加至系统角色，不能通过编辑移除；C 的修订权限和发布后记忆整理边界同样保留。

**模型继续返回纯文本，不恢复 JSON / JSON Schema。** 服务端按既有纯文本标题协议解析和保存；内部数据库快照、配置 API 的 JSON 不属于模型输出方式。仅有书名等自由文本的阶段保持对应输出范围。

## 保存、历史与会话

- 一对提示词和阶段规则共用版本号；配置与历史在同一短事务保存。
- 保存只影响之后进入网关的请求，已发出请求继续使用冻结配置。
- 读取不创建配置行；默认值集中加载，不另维护展示副本。
- 并发编辑过期返回 409，不覆盖他人修改。可保留编辑并读取最新版本后再保存。
- 恢复默认同时清除系统提示词、用户提示词与阶段规则，追加 RESET 历史，不删除旧版本。
- 历史展示最近 50 版，两种提示词均可查看和载入；载入不立即保存。
- 任务保存实际发送的系统和用户消息，不回填历史任务，不自动重跑或发布小说内容。
- Codex 规划阶段仍共享 `STORY_PLANNING` 会话。共用会话的配置版本由全部规划模板版本聚合，不因阶段切换而另开会话；任一规划模板保存或恢复后，下一次调用轮转一次会话，避免沿用旧规则。正文等独立阶段的配置变更不轮转规划会话。
- 独立写作和 B/C 检查沿用既有新会话策略；DeepSeek 保持无状态。
- HTTP 请求日志对新旧提示词字段均仅记录字符数、版本等元信息，完整请求在有权限的任务详情查看。

## 接口与存储

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/settings/prompts` | 当前用户目录、配对配置和默认值 |
| `GET /api/v1/settings/prompts/{key}` | 指定模板 |
| `PUT /api/v1/settings/prompts/{key}` | 保存 `{systemPrompt, sessionSystemPrompt, guidance, version}` |
| `POST /api/v1/settings/prompts/{key}/reset` | 恢复默认，提交 `{version}` |
| `GET /api/v1/settings/prompts/{key}/history` | 最近 50 个版本，包含配对文本 |

字段名兼容现有阶段数据：`systemPrompt` / `system_prompt` 仍表示用户阶段指令；新增 `sessionSystemPrompt` / `session_system_prompt` 表示真实系统角色。这一命名区别不影响页面上的准确标签。

V055 为 `user_agent_prompt` 和 `user_agent_prompt_revision` 增加可空 `session_system_prompt`，空值表示默认系统提示词，不覆盖既有阶段配置或历史。系统与用户文本分别独立按模板键存储。配置事务不跨越模型等待，无新依赖。

## 加载与验证

后端重启后由 Flyway 加载 V055；未重启时尚不能读取或保存新增字段。不要在有进行中模型任务时未经确认重启。

日常只串行运行改动相关验证，不启动全量 Spring / 模型调用：

```powershell
cd apps/server
.\mvnw.cmd -Dtest=AgentPromptServiceTest,AgentPromptControllerTest,StructuredModelGatewayTest,HttpLogSanitizerTest test
# 加载本地数据库配置后，使用随机隔离 schema 验证迁移与原子保存
$env:NOVEL_PROMPT_DB_TEST = 'true'
.\mvnw.cmd -Dtest=AgentPromptRepositoryDatabaseTest test
```

```sh
cd apps/web
npm run test:unit:full -- src/__tests__/prompt-settings.spec.ts
npm run type-check
```

这些验证覆盖配对存储、用户/模板隔离、默认恢复、历史载入、冲突保留、真实系统和用户位置、纯文本请求、会话版本与日志脱敏，不代表真实文学效果已评测。

2026-10-09 本地部署：作者授权重启后，检查 19 个项目的模型与自动任务均无进行中记录，加载 V055，后端 8081 健康状态 UP，前端 5173 代理接口返回 200。23 份既有阶段提示词与规则的指纹、版本和自定义状态重启前后完全一致。31 项相关后端测试、3 项隔离数据库验证、前端 8 项单元与 2 项单尺寸浏览器交互通过，类型与改动文件 ESLint 通过；未跑全量测试、未修改现有项目资料或调用真实模型。
