# Novel Agent 服务端开发上下文

> 本文件是服务端面向 AI 编程助手和开发者的持续维护文档。它描述当前代码，而不是只描述理想架构。进入 `apps/server` 开发前先阅读本文件；完成后端功能、迁移、接口或架构调整后，必须同步更新相关章节。

## 1. 项目目标

Novel Agent 是面向长篇小说作者的可控创作系统。服务端负责：

- 管理项目、创作意图、故事方向、故事圣经和分层大纲。
- 生成章节合同、正文候选和审稿报告。
- 让作者决定哪些正文和候选事实进入正史。
- 使用 PostgreSQL 保存权威业务数据和正史版本。
- 使用 pgvector 召回历史正文与摘要。
- 使用 Neo4j 查询人物、事件和事实关系。
- 使用 Kafka + Outbox 异步更新可重建投影。
- 对 Codex、DeepSeek 和本地模板提供统一的领域生成入口。

系统不允许模型直接写正史。模型只能生成候选，所有高风险变更必须经过服务端校验和作者确认。

## 2. 技术基线

| 类别 | 当前技术 |
| --- | --- |
| Java | Java 21 |
| Web | Spring Boot 3.5 |
| Agent 编排 | Spring AI Alibaba Agent Framework 1.1.2.2 |
| 持久化 | Spring Data JPA、PostgreSQL 16、Flyway |
| 向量检索 | PostgreSQL pgvector，统一 1024 维；本地或 OpenAI-compatible Embedding |
| 图谱 | Spring Data Neo4j、Neo4j |
| 消息 | Spring Kafka、Kafka KRaft |
| 模型 | Codex App Server、DeepSeek Responses、本地模板 |
| 测试 | JUnit 5、Spring Boot Test |

Maven Wrapper 位于本目录。不要假设机器安装了全局 Maven。

## 3. 权威文档顺序

发生描述冲突时，按以下顺序判断：

1. 当前代码和 Flyway 迁移：代表实际运行状态。
2. 本文件：代表当前开发上下文和近期路线。
3. 根目录 `README.md`：代表整个项目的能力概览。
4. 根目录各设计文档：代表完整目标和长期方案。

关键设计文档：

- `../../NOVEL_AGENT_SYSTEM_DESIGN.md`
- `../../NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`
- `../../NOVEL_AGENT_DOMAIN_MODEL.md`
- `../../NOVEL_AGENT_WORKFLOW_DESIGN.md`
- `../../NOVEL_AGENT_AI_SPEC.md`
- `../../NOVEL_AGENT_STAGES_AND_PROMPTS.md`
- `../../NOVEL_AGENT_API_DESIGN.md`
- `../../NOVEL_AGENT_TEST_PLAN.md`

设计文档中的接口和表不一定已经实现。开发前必须通过 Controller、Repository 和迁移文件确认现状。

## 4. 当前完成度

最后更新：2026-09-28。

### 4.1 已完成

- 项目创建、列表、详情和创作意图更新。
- 三个故事方向生成、版本保存和方向确认。
- 故事圣经生成、编辑、发布和版本关联。
- 全书、卷/幕、章节三级大纲生成、编辑和发布。
- 模糊字数预算：整书目标允许约一万字浮动，章节字数只是建议区间。
- 章节合同生成、编辑和确认。
- 整章正文生成、编辑和作者确认。
- 章节审稿、问题处理、候选事实接受或拒绝、审稿门禁确认。
- 正文、接受事实、正史版本与 Outbox 的 PostgreSQL 原子提交。
- Outbox 定时发布 Kafka。
- pgvector 与 Neo4j 独立消费组投影和幂等检查点。
- 长期记忆召回：章节摘要优先、正文片段补充、定向图谱事实、统一 Token 预算。
- 章节合同、正文和审稿使用阶段白名单只读工具：近期章节摘要、语义历史检索和相关正史事实。
- 长期记忆按阶段分配预算，并根据模型上下文、固定输入、输出预留和安全余量动态收缩。
- Neo4j 不可用时，写作召回降级到 pgvector。
- Codex App Server 长驻进程；大纲每次生成新 thread，其他阶段按项目、工作流复用 thread。
- DeepSeek Responses JSON Schema 结构化输出。
- Spring AI Alibaba Graph 编排六个标准创作模型阶段的输入校验、模型生成和输出校验。
- 已确认作品导入可通过两个专用模型阶段反推故事圣经和大纲草稿，并区分已发生章节与未来规划。
- 写作生成已拆分为 Prompt、Schema、解析、本地模板、模型路由和稳定网关门面。
- 正史提交、Outbox、pgvector 投影和 Neo4j 投影具备第一批单元测试护栏。
- 人物命名可从故事圣经初始化，并通过稳定实体 UUID 支持正式姓名、昵称和称谓配置。
- 正文内部保存稳定人物引用，故事圣经、大纲、Prompt、正文预览、导出及新向量投影按当前配置渲染姓名。

### 4.2 部分完成

| 能力 | 当前实现 | 仍缺内容 |
| --- | --- | --- |
| 正史事实 | 新提交同步物化类型化实体、事件、状态、知识、关系和伏笔，同时保留 JSONB 审计快照 | V011 前旧提交的可审计回填 |
| Neo4j | 通用事实与实体、事件、状态、关系、知识、伏笔类型化投影，含项目版本水位 | 有效区间关闭与管理端全量重建 |
| pgvector | 正文章节级语义文档 | 场景、人物状态、事件、规则和伏笔分块 |
| Embedding | 本地 1024 维特征哈希；正式 OpenAI-compatible 适配器；按模型名自动回填 | 配置正式供应商密钥并做召回效果对比 |
| 审稿 | 模型审稿和本地模板 | 确定性连续性规则引擎 |
| 运行记录 | 统一 AgentRun、Prompt 摘要及新任务的完整 System/User Prompt、估算 Token/成本、耗时和失败记录 | 供应商真实 usage、重试、取消和恢复 |
| 版本 | 各生成产物保留版本 | 版本列表、Diff、恢复和正史回滚 API |
| 投影恢复 | Kafka 重试和检查点 | 失败表、人工重放、全量重建 |

### 4.3 尚未实现

- 指定位置续写、局部改写和多候选正文。
- 全项目一致性扫描。
- 影响分析和未来章节动态重规划。
- 正式用户认证与多用户权限。

## 5. 目录与职责

```text
src/main/java/com/novelagent
├── agent         Agent 阶段定义、只读 Tool、白名单与工具编排
├── project       项目、创作入口、创作意图和当前版本指针
├── planning      故事方向、故事圣经、大纲、模型生成适配
├── writing       章节合同、正文、审稿和候选事实
├── memory        pgvector 召回、图谱查询、Embedding、Token 预算
├── canon         正史提交、Outbox、Kafka 和投影
└── platform      Web 配置、统一异常处理等横切能力
```

每个业务模块优先采用：

```text
api             Controller、请求和响应 DTO
application     用例服务、工作流和端口
domain          实体、值对象、状态机和领域规则
infrastructure  Repository、外部模型、数据库和消息适配
```

不要让 Controller 直接操作 Repository。领域状态转换放在领域对象中；跨聚合用例和事务边界放在 application 层；供应商 SDK、SQL、Cypher 和 HTTP 调用放在 infrastructure 层。

## 6. 核心领域链路

### 6.1 从创意到大纲

```text
NovelProject + CreativeIntent
  -> StoryDirectionSet
  -> selected StoryDirectionCandidate
  -> StoryBibleVersion(PUBLISHED)
  -> OutlineVersion(PUBLISHED)
```

约束：

- 规划生成支持 `REVISE` 和 `REGENERATE`：前者携带基准版本做有限调整，后者不携带旧版并从上游资料重新生成。大纲默认使用最新版本，也可指定项目内任意历史大纲版本，来源 ID 随新草稿保存。
- 基准大纲以完整 JSON 进入 Prompt；大纲 Codex 生成每次新建 thread，避免旧轮次内容干扰。当前只做基本结构校验，没有自动保证每个未受影响字段保持原值。
- `OutlineModelPromptFactory` 按任务、依据优先级、完整故事圣经 JSON、篇幅参考、作者要求、生成方式和输出检查分段。首次生成章节全为 `PLANNED`；基于历史版本调整默认保留未受影响的卷章、编号及 `OCCURRED` 状态，模糊字数和建议章节数不得单独触发结构重写。
- `GET /outlines/latest` 是最新生成版本；`GET /outlines/current` 按项目 `currentOutlineVersionId` 返回实际写作版本。历史大纲可通过原有发布动作设为当前版本，不会改动较新版本；写作前端必须读取 `current` 而非 `latest`。
- `OutlineService.generate` 在调用工作流前必须对故事圣经和选定基准大纲执行 `CharacterNameService.render`；否则模型会看到旧人名，而页面预览已是新名字。历史版本本身不被这一步修改。
- 两种方式都创建完整新版本，不覆盖已发布版本；请求未指定模式时默认按 `REVISE` 处理，首次生成自然回退为重新生成。
- 故事方向、故事圣经和大纲的 `REVISE` 输出必须包含 `changeSummary` 中文修改清单并随版本持久化；首次生成和 `REGENERATE` 使用空清单。
- 故事圣经必须来源于已确认方向。
- 大纲必须来源于已发布故事圣经。
- 项目上的 `currentStoryBibleVersionId` 和 `currentOutlineVersionId` 是当前指针。
- 章节合同引用来源大纲版本；旧大纲合同不能用于当前正文生成。
- 正文生成支持 `REVISE` 和 `REGENERATE`：前者携带上一版完整正文并返回持久化的 `changeSummary`，后者不携带旧正文且修改清单为空。

### 6.2 从章节合同到正史

```text
ChapterPlan
  -> ChapterContractVersion(APPROVED)
  -> ManuscriptVersion(AUTHOR_ACCEPTED)
  -> ChapterReviewVersion(APPROVED)
  -> CanonCommit
  -> OutboxEvent
  -> Kafka
  -> pgvector / Neo4j projection checkpoint
```

正史提交入口：

```text
POST /api/v1/projects/{projectId}/chapters/{chapterNumber}/canon-commits
```

事务必须同时完成：

1. 校验项目所有权和 `expectedCanonVersion`。
2. 校验审稿属于目标章节且已经确认。
3. 校验关联正文已经由作者确认。
4. 只选择 `ACCEPTED` 候选事实。
5. 创建 `CanonCommit`。
6. 项目正史版本加一。
7. 写入 `OutboxEvent`。

pgvector 和 Neo4j 不参与该数据库事务。投影失败不能回滚已经成功的正史提交。

### 6.3 长期记忆召回

入口：

```text
NovelMemoryService.recall(stage, projectId, chapterNumber, canonVersion, query, budget)
```

输入 query 由章节标题、POV、目标、核心事件和作者本次要求组合。召回包括：

- 不高于目标正史版本的语义文档。
- 与查询中的实体或事实相关的 Neo4j 事实。
- Neo4j 无命中时的少量近期事实回退。
- Neo4j 异常时的 pgvector-only 降级。
- `MemoryBudgetAllocator` 对摘要、正文片段和图谱事实进行预算裁剪。

受控工具链：

```text
AgentToolPolicy
  → RecentChapterSummariesTool
  → StoryMemorySearchTool
  → RelatedCanonFactsTool
  → MemoryBudgetAllocator
  → NovelMemoryContext
```

当前工具由 Java 按阶段白名单调用，不是模型自主多轮 Tool Calling。内部数据库能力优先使用 Java Tool 层；只有外部资料源和跨进程能力才考虑 MCP。

硬约束：

- 不得读取高于目标 `canonVersion` 的记忆。
- 章节摘要优先于长正文片段。
- 预算计算后的最终 Prompt 必须再次估算，不能只按单项估算相加。
- 合同、正文、审稿的默认长期记忆预算分别为 5000、8000、6000 Token。
- 模型容量不足且有效预算低于阶段最低值时停止生成。
- PostgreSQL 权威查询失败时不能假装降级成功。

## 7. 模型与 Agent 实现

### 7.1 统一供应商枚举

`ModelProvider` 当前包含：

- `LOCAL_CODEX`
- `DEEPSEEK`
- `LOCAL_TEMPLATE`

API 层只传递供应商选择，领域服务通过对应 Registry 或 Gateway 路由。不要在 Controller 中判断供应商。

### 7.2 Codex

Codex 使用后端主机上的 `codex app-server`，不是前端浏览器或用户电脑上的临时进程。

核心类：

- `CodexAppServerClient`：进程、协议、thread、turn 和结构化输出。
- 本地 Codex 长文本任务默认最多等待 600 秒；运行后端的系统用户必须能够读写自己的 `.codex` 状态目录，否则 App Server 无法初始化。
- `CodexAgentSession`：项目与工作流对应的持久化会话。
- `CodexAgentSessionRepository`：恢复 thread。
- 大纲生成例外：每次 `startThread` 并替换工作流记录的 thread；故事方向、圣经、写作等阶段仍按既有规则恢复。
- 各 `CodexAppServer*Generator`：领域生成适配。

不要退回到每次调用 `codex exec` 的一次性进程方案。结构化输出继续使用 JSON Schema，并在 Java 端再次做领域校验。

### 7.3 DeepSeek

`DeepSeekStructuredOutputClient` 调用供应商 Responses API。供应商返回 JSON Schema 结果后仍需：

1. JSON 解析。
2. 必填字段检查。
3. 枚举和范围检查。
4. 领域规则检查。

网络、鉴权和限流失败不要伪装成格式错误。只有明确的结构解析失败才允许受控修复或重试。

### 7.4 本地模板

本地模板用于：

- 无外部模型时跑通开发流程。
- 单元测试和界面演示。
- 判断失败来自工作流还是模型服务。

本地模板不代表正式内容质量，不应为追求文学生成效果持续堆复杂逻辑。

### 7.5 Spring AI Alibaba

六个标准创作模型阶段均使用 Graph 编排“输入校验 -> 生成 -> 输出校验”。导入反推目前由 `ImportedPlanningService` 顺序编排“故事圣经反推 -> 大纲反推”，每个步骤各调用一次模型并执行结构化解析和领域校验。长期记忆工具在写作 Graph 前受控执行，Graph 的生成节点正常只调用一次模型。

Codex 是有状态 Agent Runtime，不强制包装成无状态 `ChatModel`。DeepSeek 和其他稳定支持的标准模型可以使用 Spring AI 抽象；供应商原生能力缺失时通过受控适配器补齐。

## 8. 数据库与迁移

迁移目录：

```text
src/main/resources/db/migration
```

当前迁移：

| 版本 | 内容 |
| --- | --- |
| V001 | 项目与创作意图 |
| V002 | 故事方向 |
| V003 | Codex 会话 |
| V004 | 故事圣经 |
| V005 | 大纲 |
| V006 | 章节合同与正文 |
| V007 | 审稿 |
| V008 | 正史提交、Outbox、语义文档、投影检查点 |
| V009 | Embedding 模型与 HNSW 索引 |
| V010 | 分层记忆摘要 |
| V011 | 类型化正史实体、事实、事件、状态、关系、角色知识和伏笔 |
| V012 | 实体别名、文本提及与稳定 ID 解析审计 |
| V013 | 已有作品原件、解析报告和导入章节 |
| V014 | pgvector 统一升级为 1024 维 |
| V015 | Agent 运行、Token 与费用记录 |
| V016 | 类型化 Neo4j 投影历史重放 |
| V017 | 导入反推规划来源、状态及章节已发生/待规划标记 |
| V018 | 导入规划模式：故事素材改编或已有正文续写 |
| V019 | 人物命名档案、来源名称、角色键和独立乐观锁版本 |
| V020 | 人物性格、背景、性别等人物档案字段 |
| V021 | 规划版本的模型修改说明 |
| V022 | 正文版本的模型修改说明 |

人物引用规则：

- 正文内部格式为 `{{entity:<UUID>:CANONICAL|NICKNAME|TITLE}}`，禁止新增 `role1` 等顺序占位符。
- API 与导出不得向作者暴露内部引用；统一通过 `CharacterNameService` 渲染。
- 调用模型前使用当前姓名，模型正文落库前再由 `CharacterNameService.tokenize` 转为稳定引用。
- 改名只更新 `story_entity` 名称档案并登记曾用名，不批量覆写历史版本。

迁移规则：

- 已应用迁移禁止修改，新增 `V011__...sql` 及后续版本。
- PostgreSQL 表和 JPA 映射必须同步修改。
- 迁移应兼容已有数据，新增非空字段要先回填再设约束。
- pgvector 类型显式使用目标 schema 中的 `public.vector`。
- Neo4j 是投影，不通过 Flyway 保存权威事实。
- 不把数据库地址、密码或模型密钥写入迁移、代码、测试和本文档。

## 9. Kafka、Outbox 与投影

正史提交写入 `outbox_event`，`OutboxPublisher` 批量读取未发布事件并发送 Kafka。消息只包含 ID、章节号和正史版本，不包含完整正文。

当前消费者：

- `VectorProjectionConsumer`：读取正文、生成 Embedding、upsert `semantic_document`。
- `GraphProjectionConsumer`：读取接受事实、upsert Neo4j 节点和关系。
- `ProjectionCheckpointStore`：按 `(event_id, projection_type)` 保证幂等。

约束：

- pgvector 和 Neo4j 必须使用不同 consumer group。
- 消费前检查 checkpoint，完成后再记录 checkpoint。
- 重复消息不能生成重复语义文档或图谱事实。
- 捕获异常后必须让 Kafka 感知失败，不能记录成功 checkpoint。
- 投影状态按 `PGVECTOR` 和 `NEO4J` 分别展示。

后续失败重放和全量重建设计见 `../../NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`。

## 10. API 现状

当前 Controller：

| Controller | 主要能力 |
| --- | --- |
| `ProjectController` | 项目创建、列表、详情、创作意图 |
| `StoryDirectionController` | 方向生成、读取、选择 |
| `StoryBibleController` | 故事圣经生成、编辑、发布 |
| `OutlineController` | 大纲生成、编辑、发布 |
| `WritingController` | 合同、正文、审稿的生成、编辑和确认 |
| `CanonCommitController` | 正史提交 |
| `ProjectionStatusController` | Kafka、pgvector、Neo4j 投影状态 |
| `MemoryController` | 长期记忆召回预览 |
| `TypedCanonController` | 实体、状态、别名、提及、事件、伏笔、人物关系和知识边界查询 |

接口设计原则：

- 路径统一以 `/api/v1` 开头。
- 项目资源必须验证当前用户所有权。
- 编辑使用 JPA `rowVersion` 或请求中的期望版本防止静默覆盖。
- 生成新候选应创建新版本；发布状态的内容不可原地编辑。
- 非法状态转换抛出明确业务错误，由 `ApiExceptionHandler` 转为统一响应。
- 新增高风险写接口时设计幂等键，不能只依赖按钮防重复点击。

## 11. 配置

主配置：`src/main/resources/application.yml`。

敏感值只通过环境变量或 `.env.local` 提供。需要关注的配置组：

```text
spring.datasource.*
spring.neo4j.*
spring.kafka.*
app.ai.codex.*
app.ai.deepseek.*
app.kafka.*
app.memory.*
```

`.env.local` 被版本控制忽略。示例变量写入根目录 `.env.example` 时只能使用无效占位值。

## 12. 编码约定

### 12.1 Java

- 不使用通配符导入。
- 一个源文件一个主要类型；小型私有 record 或 enum 可以内嵌。
- 构造器参数和复杂方法调用逐行排版。
- 不把多个声明、赋值或分支压在同一行。
- 方法名表达业务意图，例如 `requireApprovedReview`，避免 `handle`、`process` 等模糊名称。
- 复杂 SQL 和 Cypher 使用命名常量与 Java text block。
- Controller 保持薄；超过约 150 行的应用服务应检查是否混合了模型、Schema、Prompt、解析或持久化职责。
- 捕获异常必须记录、转换或重新抛出，不能静默吞掉。
- 只有解释业务原因时写注释，不写逐行翻译式注释。

### 12.2 事务

- 正史提交和 Outbox 写入必须在同一个 PostgreSQL 事务中。
- 外部模型调用、Kafka 等待和 Neo4j 网络调用不要包在长时间数据库事务内。
- `@Transactional(readOnly = true)` 用于纯查询用例。
- 跨 PostgreSQL 和 Neo4j 不使用分布式事务，采用 Outbox + 幂等投影。

### 12.3 领域边界

- API DTO 不直接承担领域状态机。
- 模型输出 DTO 必须经过转换和领域校验后才能持久化。
- JSONB 适合不可变版本快照和供应商输出，不适合永久替代所有可查询领域表。
- PostgreSQL ID 是跨存储稳定标识，禁止把 Neo4j 内部 ID 写回业务表。

## 13. 测试与验证

后端完整测试：

```powershell
.\mvnw.cmd test
```

最低验证要求：

| 改动 | 必须验证 |
| --- | --- |
| 领域对象或状态机 | 单元测试覆盖合法和非法转换 |
| Controller / DTO | 请求校验和错误响应 |
| Repository / 迁移 | PostgreSQL 集成测试或真实启动迁移 |
| 模型 Parser / Schema | 正常、缺字段、非法枚举、额外字段 |
| Outbox / 投影 | 重复消费、部分失败、检查点幂等 |
| 记忆预算 | 中英文估算、裁剪顺序、最终预算不超限 |

涉及真实 PostgreSQL、Kafka 或 Neo4j 时，先跑无外部依赖的单元测试，再做集成验证。测试日志和夹具中不得包含真实密钥或完整用户手稿。

## 14. 近期开发路线

除非用户明确调整优先级，按以下顺序推进：

### 阶段 A：代码结构与测试护栏

- 已完成：拆分 `WritingGenerationGateway` 中的 Prompt、Schema、解析、本地模板和模型路由职责。
- 已完成：为正史提交、Outbox 发布和两个投影消费者补充单元测试。
- 剩余：为 PostgreSQL 事务、Kafka 发布和两个真实投影补 Testcontainers 或测试环境集成测试。

### 阶段 B：类型化长篇正史

- 已完成：增加 `story_entity`、`story_fact`、`story_event`、`entity_state_change`、`character_knowledge`、`story_relationship` 和 `foreshadow` 表。
- 已完成：正史提交在同一 PostgreSQL 事务内同步物化可确定类型的事实；无法安全映射的事实保留为通用 `story_fact`。
- 已完成：增加实体列表、人物当前状态、事件时间线和伏笔只读 API。
- 已完成：候选事实支持六种事实类型，包含置信度、故事时间、状态前后值、人物认知、物品字段和伏笔状态；新审稿版本为 `chapter-review/2`。Codex 严格结构化输出不接受 `payload.oneOf`，因此 `WritingOutputSchemas` 使用统一 payload 对象、可空字段和枚举约束，无关字段输出 `null`，再由 `TypedFactProposalValidator` 按 `factType` 做二次领域校验。`STATE_CHANGE` 使用独立的 `stateEntityType`，避免与实体登记的宽类型枚举混用。
- 已完成：稳定实体 ID、标准名称、确认别名和显式新实体的确定性解析；每次成功解析记录 `entity_mention`，未解析代词阻止正史提交。
- 已完成：实体别名与解析记录只读 API，以及作者新增确认别名 API。
- 已完成：审稿阶段把按章节相关性和近期提及排序的实体目录交给模型，同一次审稿调用生成稳定 ID 建议，不增加模型调用次数。
- 已完成：审稿界面可为状态、关系、认知和实体候选选择已有稳定实体；代词未选择实体时无法确认审稿。
- 已完成：前端故事资料中心可浏览类型化实体、状态、别名、提及、事件时间线和伏笔，并可新增确认别名。
- 已完成：人物关系与知识边界只读 API 和前端页面，可按人物定位关系、认知真假、来源章节和证据。
- 已完成：作者可基于稳定人物 ID 维护独立人物档案，包含基础身份、背景、性格动力、表达习惯、秘密、人物弧光和行为边界；章节合同、正文和审稿统一读取非空档案。
- 剩余：更细粒度的段落级共指评分、独立歧义候选持久化，以及伏笔状态迁移合法性检查。
- 剩余：增加物品流转专用读取接口，以及旧 `accepted_facts` 的可审计回填任务。

### 阶段 C：确定性一致性

- 实现位置、生命状态、物品持有、知识泄漏、故事时间和世界规则检查。
- 在生成前和正史提交前执行。
- 合并模型审稿问题与规则问题。

### 阶段 D：导入已有作品

- 已完成：文件上传与原文保存。
- 已完成：文本解析、章节识别、类型识别和导入报告。
- 已完成：从已确认正文反推故事圣经草稿、已发生大纲和未来规划。
- 抽取候选实体与事实，经作者确认后提交。

### 阶段 E：恢复与高级规划

- 投影失败记录、人工重放和全量重建。
- 全项目一致性扫描。
- 正文 Diff、正史恢复、影响分析和未来章节动态重规划。

每个阶段完成后再扩展下一阶段，避免同时铺开大量只有接口没有闭环的模块。

## 15. 已知技术债

- `CodexAppServerClient` 负责较多协议与进程细节，需要在补足回归测试后再拆，不要无测试重写。
- 新提交已开始物化类型化正史，但模型候选仍是扁平结构，旧 JSONB 事实尚未批量回填。
- 当前运行环境仍使用开发用特征哈希；正式适配器已完成，但未配置独立 Embedding 密钥和召回评测集。
- `AgentRun` 当前使用 Token 估算；尚未接入供应商真实 usage、重试、取消和恢复。
- 当前用户身份来自固定开发用户配置，不是正式认证。

## 16. AI 开发工作流

AI 在服务端开始开发时：

1. 阅读本文件和本次功能对应的根目录设计文档。
2. 检查相关 Controller、application service、domain、repository 和最新迁移。
3. 查看当前测试，先确认已有行为边界。
4. 说明将修改的职责范围，避免顺手改动无关模块。
5. 实现、测试，并核对真实数据库或外部依赖是否需要集成验证。

AI 完成开发时，必须检查是否需要更新本文件：

- 新增或完成能力：更新“当前完成度”。
- 新增表或迁移：更新“数据库与迁移”。
- 新增 Controller 或关键接口：更新“API 现状”。
- 修改核心链路：更新“核心领域链路”。
- 修改 Agent 阶段、Prompt、输入信息或输出 Schema：更新 `../../NOVEL_AGENT_STAGES_AND_PROMPTS.md`。
- 引入新的配置或基础设施：更新“技术基线”和“配置”。
- 技术债解决或新增：更新“已知技术债”。
- 路线优先级改变：更新“近期开发路线”。
- 每次有效更新都修改“最后更新”日期。

不要把每日流水账、临时调试记录、真实连接信息或密钥写入本文件。它应保持为下一位开发者或 AI 能在几分钟内建立正确项目模型的高密度上下文。
## 17. 近期实现（2026-09-28）

- V013：已有作品导入。`WorkImportService` 使用 Apache Tika 抽取文本，原文件和解析结果在作者确认前独立保存。
- V014：向量列统一为 1024 维。`TextEmbeddingService` 默认由本地特征实现提供；`EMBEDDING_PROVIDER=remote` 时使用 `OpenAiCompatibleEmbeddingService`。
- V015：`agent_run` 记录模型阶段的运行状态、Prompt 摘要、估算 Token、费用和耗时。
- V024：新任务额外记录完整 System/User Prompt；`GET /api/v1/projects/{projectId}/agent-runs/{runId}/prompt` 只向项目所属作者按需返回，旧任务仍只有 500 字摘要。Prompt 可含手稿，禁止写入运行日志或公开列表响应。
- V016：清理旧 Neo4j 检查点并由 `novel-neo4j-projector-v4` 重放；`GraphProjectionConsumer` 同时写通用事实和类型化图谱。
- V017：已确认导入可反推故事圣经和大纲草稿；章节用 `OCCURRED`、`PLANNED` 区分既有事实和未来计划，产物保留来源导入 ID。
- V018：导入规划显式区分 `ADAPT_SOURCE` 与 `CONTINUE_MANUSCRIPT`。前者把导入内容作为可改编素材并强制所有章节为待规划，后者才保留已发生章节。
- 故事方向页可维护项目级“必须保留的信息”；生成前先保存最新创作意图。规划 Prompt 要求三个方向逐条落实，并由故事圣经继续写入 `hardConstraints`。
- 已有导入规划但没有创作意图时，前端可从导入生成的故事圣经和大纲反填创作意图；该过程不调用模型、不直接写库。
- PostgreSQL 是正史唯一真相源；pgvector 和 Neo4j 都只能作为可重建投影。
- 正式 Embedding 尚未配置独立密钥，当前运行环境仍使用 `local-feature-hash-v2-1024`。
