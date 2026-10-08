# Novel Agent 服务端开发上下文

> 本文件是服务端面向 AI 编程助手和开发者的持续维护文档。它描述当前代码，而不是只描述理想架构。进入 `apps/server` 开发前先阅读本文件；完成后端功能、迁移、接口或架构调整后，必须同步更新相关章节。

## 1. 项目目标

Novel Agent 是面向长篇小说作者的可控创作系统。服务端负责：

- 管理项目、创作意图、故事方向、故事圣经和分层大纲。
- 依据已发布大纲生成单份正文草稿、质量检查和审稿报告，不再生成或批准章节合同。
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
3. `docs/README.md`：代表整个项目的能力概览。
4. `docs/` 各设计文档：代表完整目标和长期方案。

关键设计文档：

- `../../docs/NOVEL_AGENT_SYSTEM_DESIGN.md`
- `../../docs/NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`
- `../../docs/NOVEL_AGENT_DOMAIN_MODEL.md`
- `../../docs/NOVEL_AGENT_WORKFLOW_DESIGN.md`
- `../../docs/NOVEL_AGENT_AI_SPEC.md`
- `../../docs/NOVEL_AGENT_STAGES_AND_PROMPTS.md`
- `../../docs/NOVEL_AGENT_AGENT_TOPOLOGY.md`：历史 23 种模型工作流图，当前简化图见 `../../docs/NOVEL_AGENT_SIMPLIFIED_WORKFLOW.md`。
- `../../docs/NOVEL_AGENT_API_DESIGN.md`
- `../../docs/NOVEL_AGENT_TEST_PLAN.md`

设计文档中的接口和表不一定已经实现。开发前必须通过 Controller、Repository 和迁移文件确认现状。

## 4. 当前完成度

最后更新：2026-10-08。

### 4.1 已完成

- V052 单章自动编辑闭环（2026-10-08）：DraftLoopService/Store/Context/Model 从大纲执行 A（MANUSCRIPT）→B（QUALITY_REVIEW）→C（DRAFT_JUDGE_REVISION）→B，或直接检查当前有效草稿；冻结共同创作依据，B/C 独立新会话，不要求作者本轮输入、选择问题或轮间确认。B 无评分，逐字证据、已有依据与候选补丁分开；C 逐项接受/拒绝/暂缓，仅修复计划内且不改核心事实的问题，更新全文及摘要。默认最多10轮、可选1至10；空问题、无需改、资料不足、无进展、震荡、来源变化、取消、失败均结束，上限后最后稿明确未复检。draft_loop_run 保存来源、轮次、裁决、前后稿关联与实例身份，正文只新建 DRAFT，不接受或写正史；与运行中范围任务互斥。前端正文/质量页提供入口、轮次/阶段/停止、历史、裁决与实际差异，刷新可恢复；同实例重启标 INTERRUPTED 不自动重试。公共任务记录保存 Prompt/响应/用量，自定义 C 保留权限边界；活动目录22工作流/24模板，未覆盖现有自定义 A/B。无新增依赖或真实付费调用，需重启后端加载迁移；详情见 ../../docs/NOVEL_AGENT_DRAFT_EDITORIAL_LOOP.md。

- 导入大纲缺创作意图修复与首章目标强化（2026-10-08）：OutlineService 不再仅为取得篇幅而强制独立 CreativeIntent 记录；已保存意图优先，否则沿用指定基准大纲/最新大纲的 wordBudget，不创建猜测的作者意图或额外调用。两者皆无时明确提示到故事方向保存目标字数。OUTLINE 与两种导入反推大纲、PLANNING_CHECKPOINT 的默认系统指令共享第一章吸引力要求，落实即时目标/行动压力、有铺垫的反转、题材回报及后果钩子；STANDARD 也执行第一章目标，FANQIE_GRIPPING 仍另管完整前三章短弧。作者慢热/指定开场、事实边界与有限修改优先，OCCURRED 不重写；不增加结构校验、输出字段或请求。不自动覆盖数据库自定义提示词与旧稿，需重启加载。

- 场景级大纲（2026-10-08）：OUTLINE/IMPORT_REVERSE_OUTLINE/PLANNING_CHECKPOINT 在原有请求中按章输出 sceneOutline 自由文本，合并场景清单与关键场景展开，不新增当前章展开请求、不改模型强度/令牌上限。ChapterPlan 随大纲 JSONB 保存文本及服务端 sceneOutlineNeedsUpdate；章节、幕、全书或来源圣经变更而文本未更新时保留旧稿并提示待核对。正文/试写直接读取，有效文本进入 writing_basis，失效文本不列作必写节拍。前端每章可查看/编辑，已发布版本只读。没有新 Agent/合同审批/文学结构校验/迁移/依赖；不改已有项目和自定义提示词，后端须重启。详见 ../../docs/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- 人物设计权限修正（2026-10-08）：CharacterBlueprintGuide 统一区分原创/授权改编的新设定与续写原文提炼，核心人物补具体姓名（保留已有姓名和作者匿名要求），展开经历→应对方式→需求矛盾→选择→关系后果。CHARACTER_DESIGN 不再叠加通用未知留空指令；雪花/普通圣经/导入圣经显式传递模式，圣经取消一两句压缩，developmentNotes 全文与大纲背景因果沿用。补全、准备、有限修订、审阅和正文事实边界不放宽；不增加Agent/调用/依赖/迁移，不改已有项目或作者自定义提示词。相关50项通过，全量613项（561通过、52环境门禁跳过、0失败）。需重启加载新默认规则，真实文学效果仍需实际生成评估。详见 ../../docs/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- V051 雪花渐进规划（2026-10-07）：SnowflakePlanningService 将新故事/已确认导入按核心与一段梗概→统一人物设计自由文本→世界构建→三幕情节串行扩展，再整合圣经；有限修订不重跑四步。CORE/WORLD/PLOT 共用 SNOWFLAKE_PLANNING，人物仍 CHARACTER_DESIGN，目录为21工作流/23模板。FreeTextPlanningRequest 用单text传输原始自由文本，仅检查可读非空，不增加弧光、三幕或世界维度结构校验。snowflake_planning_run 保存来源快照、四步全文及 RUNNING/SUCCEEDED/FAILED/CANCELLED，失败保留前置结果但不自动恢复或复用。圣经 developmentNotes 保留完整底稿，大纲读取；作者修订后的明确字段优先于旧底稿。GET /projects/{projectId}/snowflake-plans/latest 限项目作者；圣经/导入页展示阶段状态与全文，底稿可随圣经编辑。公共网关模型配置、预算、任务响应与停止复用，私有文本日志脱敏。前文召回、状态/向量投影、作者确认及正史边界不变，没有新增依赖或真实付费调用，不改已有项目数据；重启后端加载V051。验证：服务端607项（555通过、52环境跳过），另隔离PostgreSQL/Spring三项通过；前端218单元/11交互测试通过，类型、构建、改动文件ESLint通过。详细方法、接口与验证见 ../../docs/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- V050 流程简化：当前活动目录 20 种模型工作流、22 份提示词模板。合同与合同审阅退出前端、自动创作与正文门禁，旧写入入口返回 410。正文从当前已发布圣经/大纲直接生成，持久化 writing_basis（大纲 ID、完整上下文指纹、本章计划快照）；质量、局部编辑和审稿读取同一依据，作者确认与正史提交仍显式执行。统一 CharacterDesignService 承接新故事人物设计、导入原文确认后的设计、圣经人物补全、创作准备人物世界设计，统一 CHARACTER_DESIGN 记录和可编辑提示词；驱动力三角、五阶段弧光、关系冲突、事实与未来计划边界共用。导入按人物→反推圣经→反推大纲串行，前一步失败不继续。旧数据不作为本轮兼容验收目标，生产清理范围须经作者明确。验证：全量 596 项中 546 通过、50 环境门禁跳过；另隔离 PostgreSQL/完整 Spring 装配 49 项通过，前端 213 单元与 10 浏览器交互通过，类型/构建/改动文件 ESLint 通过。无真实付费模型调用或生产资料修改；现有后端尚未重启。详细主线及可选支路见 ../../docs/NOVEL_AGENT_SIMPLIFIED_WORKFLOW.md。下列旧日期条目是能力演进记录，涉及合同的历史描述已被本条替代。

- 公共能力重构（2026-10-07）：13 个业务服务的重复项目归属校验统一调用 ProjectAccessService；保留原服务写事务、锁、来源版本及作者确认门禁，锁后实体可用 requireOwnedProject(NovelProject) 重新检查权限而不额外查询。StructuredModelGateway 拆出包内 CodexSessionManager（会话策略/版本轮转/缺失恢复/完成回写）和 StructuredRequestBudget（最终上下文容量校验），入口及默认 Prompt/Schema/输出上限/解析回调保持不变，不新增 Spring Bean。platform.support.Sha256 统一 12 个类的 13 处指纹算法，文本 UTF-8、原始字节、小写 hex、序列化内容及调用方 null 规则不变，不需要数据回填。ArchitectureTest 新增权限/指纹/网关委托三条字节码约束；新增并发哈希、预算边界、失败 turn 不回写、progress 透传和锁后归属复核测试。完整测试 598 项：默认 548 通过、50 环境门禁跳过；另外 49 项隔离数据库/完整 Spring 装配测试全部通过，合计 597 通过、1 项真实模型评测未启用、无失败。本轮无新依赖、接口变化或迁移，不操作现有项目或调用付费模型；后端尚未重启加载本轮改动。详细当前结构见 ../../docs/NOVEL_AGENT_JAVA_ARCHITECTURE.md。

- V049 全局提示词管理：新增 prompt 模块，AgentPromptDefaults 与原生成器共用默认系统指令；AgentPromptCatalog 覆盖 23 个工作流、25 份模板（导入反推圣经/大纲各分改编和续写）。当前用户配置与不可变历史保存在 user_agent_prompt/user_agent_prompt_revision，GET/PUT /settings/prompts、POST /{key}/reset、GET /{key}/history；两个文本各限 40000 字符，更新带 version，过期 409，读取不写入。StructuredModelGateway 在预算/记录/供应商请求前读取并冻结配置，支持系统指令替换和阶段规则；未编辑请求文本不变，动态项目资料、作者要求、Schema/令牌预留/解析/门禁不改。自定义文本追加固定事实/知识/作者优先级/候选输出边界，任务保存实际文本与配置版本，不改历史结果。codex_agent_session.prompt_revision 变化（包括 RESET）时换新会话，同版沿用旧策略；DeepSeek 无状态。前端 /settings/prompts 支持搜索、URL阶段恢复、保存、默认/边界查看、最近50版载入、并发冲突与未保存保护；不是修改风格预设目录或运行第三方 skill。无新增依赖、无真实模型调用；需要重启后端加载，详细范围与验证见 ../../docs/NOVEL_AGENT_PROMPT_MANAGEMENT.md。
  验证：全量 584 项（534 通过、50 环境门禁跳过），另外隔离 PostgreSQL/完整 Spring 装配 4 项通过，实际 API 保存后的文本由网关发送到模型替身并记录，RESET 不回填历史或写正史；前端 211 单元/23 相关浏览器测试通过，构建/类型/ESLint/格式检查通过。新提示词字段在 HTTP 日志仅记录字符数与版本。自动重启被环境限制拦截，当前旧后端未停止，现有项目 schema 尚未加载 V049，需手动重启；不将隔离测试迁移当成生产部署成功。

- 作者要求与策略分离：大纲生成不再将系统策略拼入作者 instruction，OutlineService、Graph 和三种生成器分别传递作者原文与 CreativeStrategyPolicy；Codex/DeepSeek 共用 Prompt 工厂，作者要求前置，策略独立标注系统来源。有效上游约束、OCCURRED 和已确认事实仍不可越过；在边界内作者明确开场、回忆框架与节奏优先于通用策略，空要求不授权整体重写，不能从作者原文猜测项目策略。调整冲突在既有 changeSummary 说明，上游变更仍需作者操作；共享策略指南补充相同边界，不新增模型步骤、Schema、接口或迁移，不追溯修改任务 Prompt 和旧稿。全量 562 项：516 通过、46 环境门禁跳过、无失败；覆盖作者开头保留、空要求、策略独立传递及两个供应商入口，均用模型测试替身，未对真实文学质量作保证。后端需重启加载。实际模型工作流仍为 23 种，记忆预算的 AgentStage 六项不是完整清单，详见 ../../docs/NOVEL_AGENT_STAGES_AND_PROMPTS.md。

- 业务分层注释：128 个服务、控制器、Repository/JDBC 存储和响应转换文件补充中文职责说明，557 个方法补充操作边界及参数说明；关键处注明项目归属、行版本与生成序号、短事务与模型等待、规划与正史、作者确认与发布、幂等及迟到结果隔离。当前无独立 MyBatis Mapper，映射注释位于实际 JDBC 行转换、JSON 恢复和响应转换方法，不新增空层。新增说明之外仅有行尾格式变化，未修改业务代码、SQL、Prompt、接口或依赖。验证：逐文件移除本轮新增注释后与修改前来源一致（忽略换行风格及文件末尾空行）；后端全量 556 项，510 通过、46 环境门禁跳过、无失败。后续修改行为时应同步维护相邻注释，不能将注释视为替代代码与测试的业务保证。

- 强开篇提示词强化：CreativeStrategyGuide 共享策略边界和各阶段落点；FANQIE_GRIPPING 要求首段当前矛盾、眼前欲望／回应代价、行动选择与类型回报，禁止用无因果接入的远期预告或完整讲题流程充当开场推进。完整大纲／分块规划在现有字段落实三章短弧，合同细化入场与回报节拍，正文压缩无效操作；试写及独立审阅检查片段已展开的压力，质量／合同／三章审阅区分上游缺设计和执行不到位。不添加模型步骤、Schema、评分维度、迁移或自动重写；STANDARD、OCCURRED、视角知识与有限修订边界不变，策略规则不受可选 craft-rules-enabled 开关关闭。导入改编／续写原来未传项目策略，现两次原有请求均传入同次读取的策略指南，大纲补共享落点；续写已发生章仍不可修改。已有大纲／合同／试写不会自动变好，须作者明确调整上游并重新生成；真实阅读吸引力尚未对照评测。
  本轮验证：全量 556 项中 510 通过、46 环境门禁跳过；另行隔离 PostgreSQL 导入测试 1 项通过，验证两次请求策略与仅保存草稿，模型使用测试替身。没有调用真实模型或改真实项目资料。自动重启被执行限制拦截，需重启后端加载新代码。

- V048 新增数据库预设“校园关系：清爽叙事”（campus-relationships/v1），按主体朴素行动、日常人情声口、情感落差观察、少量第一人称自嘲组合具体技法；保存六维字段、八维技法与两组原创正反例，保持事实／知识边界和既定视角，强开篇仍服从项目策略，不为风格强造暗恋或转学等情节。现有数据库列表、应用、试写、推荐和共享检查／润色指南直接复用，不改默认风格或任何项目已选配置，不新增作家身份模式；旧 11 个预设版本不变。

- 通用模型停止：生成状态面板对运行中的请求／模型任务提供停止按钮；POST generation-requests/{requestId}/actions/stop 与 agent-runs/{runId}/actions/stop 先校验项目归属，再中断本进程的准确模型调用。HTTP 通过 X-Generation-Request-Id 关联 Recorder；停止和输出处理入口互斥，已停止的迟到响应不进入解析／产物保存，已经进入保存阶段则返回 409，不承诺撤销完成结果。AgentRun 持久化 CANCELLED、已有响应／可得用量，SSE／前端识别已停止；原文解析和创作准备任务同时标记 CANCELLED。Codex 发送 turn/interrupt，未确认结束前阻止会话复用；DeepSeek 改用可中断 JDK HTTP 等待，不保证供应商停止计费。无依赖／迁移；不是单纯中断浏览器 fetch。运行注册表仅限当前进程，升级前任务、尚未进入模型和保存中请求不能停止；自动创作外层任务仍按原恢复规则处理，详见 ../../docs/NOVEL_AGENT_GENERATION_STOP.md。

- 人物档案整合：CharacterBlueprint 增加可空 gender（40）与 ageDescription（100），旧 JSONB 缺失归一为空，保留旧 Java 构造入口；StoryBibleOutputSchema/人物补全/创作准备共用结构，发布及准备确认同步独立档案的性别年龄，仅填空白，不覆盖作者已有值。指南禁止从姓名、外貌、年级猜测确切年龄，无新迁移/依赖。前端人物命名、人物实体事实、规划和正史关系共享一个人物档案入口；非人物实体单独保留。关系读取仍使用原规划/正史来源，不写新事实边；补全只创建待发布圣经 DRAFT。未保存编辑保留原行版本，刷新不悄悄升级乐观锁。后端需重启加载，真实旧项目未调用模型或改资料，详见 ../../docs/NOVEL_AGENT_CHARACTER_DOSSIER.md。

- HTTP 请求日志：platform.web 下 Filter + HandlerInterceptor + BodyAdvice 关联 /api/** 的 HTTP_START / PARAMETERS / INPUT / END，包含服务端生成的 X-Request-Id、路径所属 projectId、参数／JSON 摘要、响应状态、耗时；MDC 将同请求线程的模型日志关联，退出恢复原值，异步线程不自动继承。无所属项目用“-”，创建项目成功后 END 补新项目 ID。输入仅读转换器已读取的 JSON，不重复读取原始流；无效 JSON／MVC 前拒绝时只记状态和未读标记。文件仅元数据、二进制仅字节数，SSE 仅连接与完成信息，不缓存或逐条记录流。凭据字段递归脱敏，已知手稿／Prompt／响应文本字段隐藏；摘要最多 4000 字符、20 数组项及深度／节点限制。HTTP_LOG_LEVEL 默认 INFO，可设 OFF，沿用滚动日志；不新增依赖／迁移，不改变导入业务流程。详见 ../../docs/NOVEL_AGENT_HTTP_LOGGING.md。

- 原文解析输出修复：ImportAnalysisOutputParser 在模型边界检查字段与类型，错误保留 items / evidence 索引及安全的领域原因。非线索项的有效 progress 归一 NOT_APPLICABLE，UNKNOWN 分类仍拒绝；唯一逐字引文确定性纠正序号，重复引文／假引文继续拒绝。IMPORT_SOURCE_ANALYSIS 使用公共网关可选处理回调，解析及短事务保存完成后才记成功，失败保留响应、可得 usage 与 OUTPUT_VALIDATION 分类；其他旧调用边界不变。不新增迁移，不回填失败报告或自动付费重试。后端 522 项（477 通过、45 环境门禁跳过），本次未运行真实模型或数据库重放；需重启后端加载。

- V047 原文统一解析：ingest 下 ImportAnalysisStore/Runner 与领域校验分离；按所选完整章节分段保存人物/世界/关系/事件/线索/伏笔，FACT/INFERENCE/UNKNOWN 和逐字证据分开。每次一个模型新会话，完成全部段后作者逐项决定并确认模式；改编支持带要求的重构，续写禁止重构原文，不采用结论不等于改写过去。reverse-plan 必须携带当前已确认 analysisId/version，两个生成请求读报告，草稿事务内重检；不发布或写正史。移除旧 80000 字符截取，超预算拒绝，不伪造完整检查。报告、恢复/取消、来源及幂等策略见 ../../docs/NOVEL_AGENT_IMPORT_ANALYSIS.md。
- V046 创作准备：CreationPreparationStore/Runner/ApprovalService/ContextService 分离来源快照、串行模型调用、确认事务和写作上下文。PREPARE 三步设计人物/实体/初始状态、剧情单元/关系/知识/时间线/明确台账、一致性报告；REVIEW 仅检查，作者选择未来章才创建大纲 DRAFT。范围按章节和剧情单元，不固定 15 万字；模型事务外，保存复核来源/行版本，显式失败恢复与取消迟到隔离。确认后档案只补空白、规划实体不写事实，当前准备指针按版本替换；合同/正文/审稿/质量/试写读取适用规划。单元节点只读，复核后正文事实与计划关联需另外确认登记台账，不自动审批或发布。隔离 PostgreSQL 34 项通过，无付费模型或生产资料变更；部署及限制见 ../../docs/NOVEL_AGENT_CREATION_PREPARATION.md。
- V045 规划资料衔接：圣经发布事务内自动建立稳定人物身份、填充独立档案的空白字段，完整蓝图保存 planning_character_snapshot；作者已有非空字段、改名和台账修改/软删除保留，不猜测覆盖。initialRelationships/relationshipDynamics 以规划叙述写 planning_relationship，前端与正文正史关系分区，不解析成未经确认的事实边。圣经/大纲增加兼容旧 JSONB 的 readerExperiencePlans 明确字段，同次模型输出、可编辑，发布时幂等导入 reader_experience_plan；来源按版本保存，替换来源标旧，不静默合并跨版本进展。正史提交同步将有效 foreshadow 接入共享台账，保留独立正文正史状态，不伪造作者事件。故事资料伏笔与伏笔承诺共用 ReaderExperiencePanel；旧项目通过 planning-materials/actions/sync 显式补同步，读取不触发模型或规划同步。旧版没有明确台账字段时为空，不猜测 openQuestions/物品/钩子；分块大纲尚不单独抽取台账。V045 本身仅确定性同步；V046 已独立实现大纲后创作准备与单元复核，详见前项。
- V044 数据库风格技法：writing_style_preset 按 preset_id/preset_version 保存完整 JSONB 档案、启用和排序，迁移初始化 11 种风格。生产无 Java 预设或 JSON 文件回退，WritingStylePresetCatalog 查询数据库；WritingStyleProfile 增加基础标识/版本及 craft 八项技法、两组原创同情境对照。项目应用保存完整快照到 settings.writingStyle，改名/编辑保留技法，当前字段和明确调整优先于基础示例；数据库更新不自动改已保存快照。完整匹配的旧档案可只读解析为初始版，同名自定义不猜测继承；只有标识但缺技法时可解析停用历史版。新增/停用/版本维护目前通过数据库，无管理 API/页面，不修改已部署迁移文件。
- 样本风格深析：STYLE_ANALYSIS 仍一次新会话，writing_style_v2 输出六项概述与 craft 八项技法；基础标识/版本必须 null、示例为空、1 至 6 条连续原文证据，解析器校验引文存在。证据仅在项目档案/页面保留，写作指南只使用规律说明，不注入样本引文本身；分析输出参考上限由 3000 调至 6000，调用次数不增但成本/耗时可能增加。推荐使用数据库当前预设快照构造 Prompt/枚举/解析，省略示例和样本引文，返回前检查目录未变。文学贴合度和真实模型评测未完成。
- V043 任务响应观测：agent_run 保存结束时的模型响应或失败部分响应、截断标记、错误类型/分类及脱敏详情；列表仅展示简短错误，不携带完整响应。GET agent-runs/{id}/response 与 GET events（SSE）经项目权限校验、no-store；SSE 每秒读取快照，15 秒心跳、30 分钟连接上限，仅观测、不触发模型或业务发布。AgentRunOutputBuffer 存本实例运行中的最多 200000 字符，结束后由 Recorder 保存并清理；崩溃前片段和多实例跨节点流式连续性未保证。Codex 接入 item/agentMessage/delta，不显示 reasoning；以 thread/turn ID 隔离旧轮次与早到通知，最终输出替换预览。DeepSeek 本轮仍为最终响应展示，不是增量流。生成 POST 仍同步等待，尚未改为后台 202 创建任务。
- Codex 等待拆分：CODEX_CLI_TIMEOUT_SECONDS 默认 600 用于协议请求，CODEX_TURN_TIMEOUT_SECONDS 默认 1200 用于生成完成。生成超时在已知 turnId 时尝试 turn/interrupt（最多再等 2 秒），未收到原轮终止通知不复用该线程；中断不保证立即停止供应商计费，无自动付费重试。强度和会话策略保持作者选择，不把流式展示当作速度提升或额度限制。旧任务未保存的错误/响应不伪造回填，本次用户明确不恢复迟到结果。当前运行进程自动重启被执行限制拦截，本轮需用户重启后端加载新代码/迁移。
- 人物规划第一批：StoryBibleContent.characterBlueprints 最多 12 人，随圣经版本保存/编辑/发布；新建或修订为 story-bible/2，旧 JSONB 缺失字段兼容为空，无新增迁移。原圣经一次生成同时设计身份背景、性格动力、声线与限制，分开开篇状态/关系/物品/知识、作者侧秘密和未来弧线；大纲、片段规划及写作 Prompt 使用共享 CharacterBlueprintGuide 边界。POST story-bibles/{id}/actions/complete-characters 要求源 If-Match，真实模型经公共网关一次 NEW_THREAD，事务外调用；CharacterBlueprintDraftStore 锁内复核并只补空白及缺失人物，保存新 DRAFT，不自动发布、不覆盖 character_profile 或正史。圣经输出预留调为 10000，补全 8000；调用数量不增不代表费用/耗时不增。来源复核不是所有姓名写入路径的原子冻结；逐章状态计划、人物一致性专项及真实文学评测待补。
- 2026-10-05 创作融合业务切片：项目 settings 保存 STANDARD / FANQIE_GRIPPING（缺省 STANDARD），权限、行版本和策略指纹校验；WritingCraftRules 为合同、审阅、正文、质量和试写增加行动/阻力、道具依据、帮助方向、兑现与钩子边界，大纲增加策略规则。前章尾段按段落保留、短章去重，下一章计划独立标注并进入正式召回预算与质量检查。普通生成在模型前后复核主要来源，但最终保存前仍存在并发窗口，不能宣称所有输入已原子冻结。
- V037 请求级审计：StructuredModelGateway 冻结本次实际供应商的模型、强度与配置版本，保存完整 Prompt、Schema、哈希、会话策略、输出预算和实际 usage（可得时）。GET /projects/{id}/agent-runs/{runId}/request-snapshot 为项目私有 no-store 查询；估算与真实值分开，缺失值为未知。客户端显式使用冻结参数；后续自动步骤仍读该供应商的当前配置，不是任务级冻结。认证失败不反复重试，超完整请求预算先拒绝、不静默截断。Codex 复用会话历史及实际输出上限可能未知。
- V038 完整前三章专项：GET opening-review 选择版本并查询来源、完整内容与预算；POST actions/check 作者主动触发一次模型新会话；八维观察必须有逐字证据，无依据 NOT_ASSESSED。模型调用在事务外，保存前锁内重检来源；本地仅规则检查，不能证明吸引力。WritingWorkbench 展示连读和报告。
- 正式质量修订默认 EXPRESSION_ONLY，只接受 STYLE/FLUENCY；显式 SCENE_STRUCTURE 才允许 LOGIC/SCENE，仍禁止新事实、改变真实事件顺序或结局。前端显示检查与润色阶段、有效/失效/跳过状态与证据定位。始终生成新 DRAFT，自动任务 INFO+FLUENCY 边界不扩大；事实保留仍需语义评测。
- V039 读者承诺/伏笔台账：计划与带 AUTHOR_ACCEPTED 正文证据的作者确认事件分开；逐字证据、姓名渲染、来源指纹、状态转换、requestId 幂等与行版本校验；正史替换等变化使旧来源失效。已有有效正史摘要按当前大纲分卷展示，不是新的模型压缩摘要。按需人物 promptContext 已接入主 Prompt，稳定引用和完整名字精确匹配，自由文本/歧义来源回退全量，不以 substring 猜测人物；档案秘密不等于视角已知。
- V041/V042 规划恢复：批次持久化从第一章开始的范围、预算、来源及片段链；先前成功计划、尝试与结果指纹进入下一块。PlanningBatchRunner 在短事务认领后调用模型，每次一个块；显式失败恢复、取消和迟到拒绝，完成后事务内原子拼装 OutlineVersion 草稿，作者沿用原编辑/发布页。已有正史/OCCURRED 项目拒绝此全书重规划，走原增量调整。请求校验已发布圣经 ID/行版本；current 圣经查询不替换成 latest 草稿。checkpoint/2 增加姓名渲染和依赖，旧 /1 结果需重建。完整前缀超预算拒绝；不是跨块语义正确性证明、任务级模型冻结或后台连续付费。V040 空号无需补空迁移。
- 局部改写：显式源正文 ID/行版本、逐字选区、occurrence 或 UTF-16 offset、授权与要求；只替换选区、保持其余正文，保存新 DRAFT 并显示差异。无改动不伪造新版本，本地模板不进行语义改写。全书巡检仅 RULES_SUMMARY_ONLY 的来源覆盖、正史摘要和台账规则，不读全书正文、不证明文学问题或遗漏兑现。
- 本轮范围和验证见 ../../docs/NOVEL_AGENT_REFERENCE_INTEGRATION_DELIVERY.md。按用户最新要求串行开发；正文每次任务生成一份草稿，优先完善单篇生成、检查、受控修订和复检，不增加批量正文供挑选的流程。novel.writing.craft-rules-enabled=false 只关闭新增写作技法块，不回退全部策略/大纲/既有风格规则。真实文学评测、已有正史的增量分块调整及 20 章连续创作回归仍未完成。
- 写作评测离线基础：测试资源 `evaluation/writing-corpus-v1.json` 固定四类故事资料、26 个场景正反例及三组完整微型三章；`WritingEvaluationBaselineTest` 从真实 WritingGenerationGateway / WritingModelRouter 截取 44 份请求，外部网关和输出解析器使用测试替身，不启动数据库或模型。显式传 `novel.evaluation.output` 可导出不可覆盖的输入 / Prompt / Schema 哈希和未运行结果模板，说明见根目录 `evaluation/README.md`。仅验证结构、来源、标签隔离与可重复性；初始标签待人工复核，真实模型、常规长度三章及读者效果未评测。未改变生产 Prompt、接口、依赖或调用数量。
- 章节自动编排第一版：V031 持久化范围任务，后台串联正文和审稿，在作者确认及正史门禁等待；支持创建幂等、取消、显式重试和超时恢复。
- V033 自动任务可选正文质量检查：创建请求与响应包含 qualityReviewEnabled，API 未提供时为 false，前端新建默认勾选；旧任务迁移为 false。草稿阶段生成/复用当前同模型质量报告；已确认正文进入原有审稿，不重复文学检查。策略不可中途变更，纳入创建幂等校验；不以质量分数代替作者门禁。
- V034 有限自动语句润色与生成额度：maxAutoRevisionRounds 默认 0，显式选择每章 1～3 轮；maxGenerationSteps 默认 100，范围 1～500。仅全部 INFO / FLUENCY 的报告可自动修订，真实模型生成新 DRAFT 并复检，其余交作者。已开始步骤计数包括失败/中断，轮数按章计数且包括失败；继续不重置额度，润色前至少剩余两次额度。上限约束任务生成阶段，不是供应商调用数或真实金额上限。
- 写作风格：11 种结构化预设（5 种基础 + 鲁迅/老舍/钱钟书/汪曾祺/王小波/余华六种技法参考）、文本/UTF-8 TXT/MD 样本分析、作者编辑应用/清除；参考预设不复制原作、不承诺复刻，限制强加方言、人物知识越界、世界规则变化及为文风增加悲剧。档案存于项目 settings.writingStyle，以项目行版本防止覆盖，正文与质量 Prompt 引入风格但不改变人物、事实与视角。服务端尚无第三方 SKILL.md 自动加载能力。
- 第一章风格试写：POST writing-style/actions/preview 接收同项目大纲 ID/行版本、显式候选风格、模型、300～1500 字目标（默认 800）及要求；允许已保存大纲草稿，不要求已确认合同。WritingStylePreviewService 通过短只读事务读取上下文，在事务外执行独立 STYLE_PREVIEW Graph，返回前复核大纲版本；不应用风格、不保存正文、不提交正史。Codex 每次新线程，真实模型调用记录 AgentRun；本地模板明确为流程演示。前端大纲新增风格试写步骤及入口，最近三份预览仅在当前会话缓存，不新增表。
- 写作技法规则复用：WritingPromptFactory 选择性改编 MIT novel-writer-skill 的文句、对白和分层修订规则，正文/试写侧保留认知与视角例外，质量侧仅提供有证据的建议；不引入硬密度指标、外部脚本、动态下载或文件工作流。固定来源版本与许可证见根目录 THIRD_PARTY_NOTICES.md，评估见 NOVEL_AGENT_WRITING_SKILL_RESEARCH.md。仍使用原有模型、Graph 和作者门禁，文学效果待评测。
- 风格执行指南：WritingStyleGuide 纯函数展开完整项目/候选快照中的六项概述、八项技法、已有示例、场景例外与润色边界，正文初稿/修订、第一章试写及质量检查共用。不按名称猜测作者，编辑不会因不再全字段匹配而丢失已保存技法。通用质感规则让位于选定风格，事实/人物/视角始终优先。质量 STYLE 检查实际叙述机制而非标签，证据仍须逐字存在于正文；上下文进入既有预算与依据指纹，旧指南的报告需重新检查。无新增模型阶段或外部依赖，文学效果未评测。
- 圣经风格推荐：POST writing-style/actions/recommend 接收同项目圣经 ID/行版本、模型与偏好；WritingStyleRecommendationService 复用 StoryBibleService 的权限与短只读查询，事务外执行 STYLE_RECOMMENDATION Graph，返回前复核版本及渲染内容。模型从 11 种预设中选一至三种，附理由、取舍和指定圣经字段中的逐字原文；拒绝重复/未知预设和伪造证据。返回规范预设档案，不修改风格、规划、正文或正史。前端读取最新已保存圣经，建议会话缓存、过期禁用载入；本地模板返回空建议及明确提示。Codex 新线程，真实模型沿用 AgentRun 记录；无新表或依赖，适配效果未评测。
- 独立正文质量检查第一版：V032 版本报告覆盖 STYLE/FLUENCY/LOGIC/SCENE，引用必须逐字存在于正文；允许检查草稿，选中建议生成修订候选。报告不参与正史审批，不替作者确认；模型质量评测与完整审稿返工尚未实现。
- 试写独立编辑：V035 保存私有报告、不可变源样例及依据指纹；STYLE_PREVIEW_REVIEW 与 STYLE_PREVIEW_REVISION 各为独立 Graph 和 Codex 新线程。检查四维并核验逐字证据，作者选中服务端报告问题后有限修订，原样例不覆盖。每报告一次尝试，失败也占用；模型调用前锁内认领，调用后复核依据。前端默认试写后检查，修订后复检，失败不自动重试；不改变项目风格、正文和正史。本地仅有限规则检查，拒绝语义修订。生成与正式质量 Prompt 共用细节功能、道具依据、帮助方向和最小修订规则，合理对照不是关键词禁令。无新依赖；文学效果待真实模型评测。

- 项目创建、列表、详情和创作意图更新。
- V036 全局模型配置：user_model_settings 按当前用户保存 provider、codex_model、codex_effort、deepseek_model 与 row_version；GET/PUT /api/v1/settings/model，携带 version 防止覆盖，过期返回 409。ChatGPT 沿用 Codex 接入，GET /settings/model/chatgpt-models 通过 model/list 读取可见模型及各自推理强度，保存时校验有效组合；目录故障不阻止改用 DeepSeek。DeepSeek 使用 deepseek-flash（当前 V4.1 Flash）或 deepseek-v4-pro。环境模型参数只作初始值，旧项目 codexModel 留存但不再生效；旧项目 Codex 设置 API 经项目权限验证后兼容读写全局 ChatGPT 参数。Codex/DeepSeek 客户端每次调用读取全局参数，前端所有新生成入口使用同一全局供应商。已发送请求不因切换被取消；已创建自动任务保留原供应商，后续步骤使用该供应商当前的全局参数，不是任务级模型快照。API 显式 provider 兼容保留，本地模板不支持导入反推；不新增密钥、依赖或登录机制。
- 三个故事方向生成、版本保存和方向确认。
- 故事圣经生成、编辑、发布和版本关联。
- 全书、卷/幕、章节三级大纲生成、编辑和发布。
- 模糊字数预算：整书目标允许约一万字浮动，章节字数只是建议区间。
- 合同流程已退役；正文无需合同或合同审阅，可直接使用已发布大纲。
- 整章正文生成、编辑和作者确认。
- 章节审稿、问题处理、候选事实接受或拒绝、审稿门禁确认。
- 正文、接受事实、正史版本与 Outbox 的 PostgreSQL 原子提交。
- 同章正史可用重新确认、重新审稿的修订稿替换；旧版留档，旧事实退役，后续章节已有正史时阻止替换。
- Outbox 定时发布 Kafka。
- pgvector 与 Neo4j 独立消费组投影和幂等检查点。
- 长期记忆召回：章节摘要优先、正文片段补充、定向图谱事实、统一 Token 预算。
- 章节合同、独立合同审阅、正文和正文审稿使用阶段白名单只读工具：前两章合同/正文/有效正史事实、语义历史检索和相关正史事实。前章优先取有效正史正文，未提交时取作者已确认稿，再退化为已确认合同；未提交稿不得当作正史。召回结果将前章合同与正文分开，正文生成优先为 n-1 正文留约 4000～5000 Token 的动态空间，n-2 较少，并预留事实额度；合同与审阅阶段使用较小的正文额度。
- 长期记忆按阶段分配预算，并根据模型上下文、固定输入、输出预留和安全余量动态收缩。
- Neo4j 不可用时，写作召回降级到 pgvector。
- Codex App Server 长驻进程；大纲和正文每次生成新 thread，其他阶段按项目、工作流复用 thread。
- DeepSeek Responses JSON Schema 结构化输出。
- Spring AI Alibaba Graph 编排八个标准创作模型阶段（含独立质量检查）的输入校验、模型生成和输出校验；样本风格分析独立调用。
- 已确认作品导入可通过两个专用模型阶段反推故事圣经和大纲草稿，并区分已发生章节与未来规划。
- 写作生成已拆分为 Prompt、Schema、解析、本地模板、模型路由和稳定网关门面。
- Java 用例职责拆分：WritingService 仅委托 ChapterContractService、ManuscriptService、ChapterReviewService；WritingContextService 统一写作上下文，QualityReviewService 直接使用 ManuscriptService。运行查询、投影状态和记忆预览 Controller 不再持有 Repository/JDBC；查询服务通过 ProjectAccessService 验证权限，SQL 留在独立查询仓库。详细职责见 `../../docs/NOVEL_AGENT_JAVA_ARCHITECTURE.md`。
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
| 运行记录 | AgentRun 请求快照、实际配置、可得真实 usage 与估算区分；automation_run 已有阶段产物、取消、重试、恢复、生成上限与有限语句返工 | 任务级配置冻结、即时取消、自动退避重试、费用归集与硬上限 |
| 版本 | 各产物保留版本，局部改写展示候选差异 | 任意历史版本 Diff、恢复和正史回滚 API |
| 投影恢复 | Kafka 重试和检查点 | 失败表、人工重放、全量重建 |

### 4.3 尚未实现

- 插入式指定位置续写和任意历史 Diff。
- 全项目语义一致性扫描（已有规则摘要来源巡检，非语义分析）。
- 全量影响分析和自主动态重规划；V046 已支持作者主动单元复核及所选未来章节的大纲草稿，不自动发布。
- 正式用户认证与多用户权限。

## 5. 目录与职责

```text
src/main/java/com/novelagent
├── agent         Agent 阶段定义、只读 Tool、白名单与工具编排
├── project       项目、创作入口、创作意图和当前版本指针
├── prompt        全局阶段指令、默认目录、配置版本和历史
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

写作内部：`WritingService` 为无事务兼容门面，仅有三个用例服务依赖。查询/作者修改的 `@Transactional` 声明位于专用服务的公开方法；模型生成入口不声明长事务。审稿打回仍在模型调用结束后由 `writingTransactionTemplate` 原子保存候选与审稿状态。质量修订使用 ManuscriptService 准备候选，由既有 QualityReviewStore 复核并保存，禁止回调门面形成循环。

## 6. 核心领域链路

### 6.1 从创意到大纲

```text
NovelProject + CreativeIntent
  -> StoryDirectionSet
  -> selected StoryDirectionCandidate
  -> SnowflakePlan(CORE -> CHARACTER_DESIGN自由文本 -> WORLD -> PLOT)
  -> StoryBibleVersion(DRAFT -> 作者发布)
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
- 已发布故事圣经不可原地更新；`POST /story-bibles/{versionId}/actions/create-revision` 接收当前页面内容并创建 `AUTHOR_EDIT` 草稿，保留方向或导入来源，不调用模型、不移动当前圣经指针。发布新草稿后才更新指针。
- 故事圣经 `REVISE` 默认以最新保存版本为基准，也可指定同项目历史版本；`REGENERATE` 不允许基准 ID。新版本以 `base_bible_version_id` 记录来源，历史列表与单版读取只允许项目所有者访问；选择基准不改变当前已发布指针。
- 正文 `REVISE` 可指定同项目同章节的历史正文 ID；未指定时使用最新保存稿。完整基准正文进入预算和 Prompt，新草稿保留 `baseManuscriptVersionId`，版本号仍按最新稿递增。`REGENERATE` 禁止指定基准正文。
- 已确认正文不可原地更新；`POST /chapters/{chapterNumber}/manuscripts/{id}/actions/create-revision` 用 `If-Match` 校验最新已确认原版，复制为 `AUTHOR_EDIT` 草稿，不调用模型、不覆盖旧正文、审稿或已提交正史。草稿保存与确认后应重新审稿；旧审稿绑定原正文版本，前端不能把它用于新稿提交。
- `GET /chapters/{chapterNumber}/canon-commits/status` 返回有效正史的提交 ID、正文 ID 和版本；普通提交仍拒绝同章第二份正史。`POST /chapters/{chapterNumber}/canon-commits/actions/replace` 接受新版已确认正文所对应的已确认审稿、当前有效提交 ID 和预期项目正史版本；旧提交保留审计、标为失效，新提交成为唯一有效正史。存在已提交的后续章节时拒绝替换，避免依赖旧事实的章节被悄悄保留。
- 合同及合同审阅阶段退役：生成、编辑和批准接口返回 410，不再用于正文门禁。
- Codex 统一人物设计与正文生成每次新建 thread；正文审稿保持既有会话策略。
- 审稿的类型化事实中，事件/状态变化的 `storyTime` 仅在正文明确给出时填写；Schema 与物化表允许为空，解析器不能因时间不明丢弃整份审稿。事件参与者也可为空；类型、证据及其余必填字段仍严格校验。
- `POST /chapters/{chapterNumber}/reviews/{id}/actions/return-to-writing` 用 `If-Match` 校验最新待处理审稿；仅接受其对应当前已确认正文。服务端按选中的审稿问题组装证据与修改建议，调用既有正文生成 Graph；模型调用在事务外，成功后使用 `writingTransactionTemplate` 原子保存新草稿并将审稿改为 `RETURNED`。新稿的 `source_review_version_id` 保留追溯，不自动确认正文或修改正史。
- 故事圣经必须来源于已确认方向。
- 大纲必须来源于已发布故事圣经。
- 项目上的 `currentStoryBibleVersionId` 和 `currentOutlineVersionId` 是当前指针。
- 正文 writing_basis 记录当前大纲及上下文指纹，依据变化不能沿用过期检查结果；本章计划快照由已发布章节字段确定性生成，不新增模型阶段。
- 正文生成支持 `REVISE` 和 `REGENERATE`：前者携带上一版完整正文并返回持久化的 `changeSummary`，后者不携带旧正文且修改清单为空。

### 6.2 从章节计划到正史

```text
Published StoryBible + Outline.ChapterPlan
  -> ManuscriptVersion(DRAFT，保存 writing_basis)
  -> 可选质量检查和润色
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

正史替换在同一事务中将旧提交置为失效、关闭旧提交的类型化事实有效区间、物化新事实并写 Outbox。实体身份可跨提交复用，不随旧事实关闭。写作工具按有效提交过滤 pgvector 和 Neo4j 结果，因此投影延迟时也不会读到旧事实；消费者收到新事件后清理旧版语义文档和图谱事实。后续章节已有正史时仍需单独的依赖分析与连锁修订流程。

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
- `MemoryBudgetAllocator` 按阶段与章节远近分配前章摘要、正文、精简合同和图谱事实额度，并确保合成后的 Prompt 记忆不超过有效预算。正文阶段 n-1 最近章节总额度不超过有效预算 65% 和 5400 Token，n-2 不超过 18% 和 1500 Token；有事实候选时预留最多 800 Token，剩余留给语义检索及其他事实。合同与审阅阶段沿用较短前文。

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
- 默认模型 `gpt-6.1-sol`；`CODEX_EFFORT` 默认 `xhigh`，由 `CodexAppServerClient` 在每次 `turn/start` 显式传递 `effort`，不是依赖 Codex 的默认推理强度。更高强度可能增加延迟和 Token 消耗。
- 本地 Codex 长文本任务默认最多等待 600 秒；运行后端的系统用户必须能够读写自己的 `.codex` 状态目录，否则 App Server 无法初始化。
- `CodexAgentSession`：项目与工作流对应的持久化会话。
- `CodexAgentSessionRepository`：恢复 thread。
- `StructuredModelGateway`：冻结提示词与模型配置，统一供应商路由和 AgentRun 记录；会话操作委托 CodexSessionManager，最终输入及预留校验委托 StructuredRequestBudget。
- `CodexSessionManager`：新建/恢复会话、提示词版本轮转、缺失 thread 重建和完成 turn 回写；模型失败不写完成标记，普通网络及鉴权错误不重试。
- `StructuredRequestBudget`：基于最终 Prompt 与 Schema 校验输入、输出预留和安全余量，不截断输入。
- `CodexSessionPolicy`：业务阶段显式选择新 thread 或复用 thread；大纲、正文及独立检查等阶段新建，连续审阅类阶段按既有规则恢复。
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

现有标准创作阶段继续使用 Graph 编排“输入校验 -> 生成 -> 输出校验”，合同阶段已退役。新建圣经与导入反推先由 `SnowflakePlanningService` 顺序执行四步自由文本底稿；导入再由 `ImportedPlanningService` 执行“故事圣经反推 -> 大纲反推”。中间文本只做传输非空校验，不新增文学结构验证；正式资料仍用现有接口格式和领域门禁。长期记忆工具在写作 Graph 前受控执行，Graph 的生成节点正常只调用一次模型。

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
| V023 | 大纲草稿的基准历史版本 ID |
| V024 | Agent 任务完整 System/User Prompt |
| V025 | 正文草稿的基准历史版本 ID |
| V026 | 章节合同草稿的基准历史版本 ID |
| V027 | 正史有效状态、被替换提交 ID 与同章唯一有效提交索引 |
| V028 | 正文草稿来源审稿 ID 与每份审稿最多一次打回生成约束 |
| V029 | 故事圣经草稿基准历史版本 ID |
| V030 | 独立章节合同审阅版本、来源合同 ID 与来源行版本 |
| V031 | 章节范围自动任务与步骤记录 |
| V032 | 来源和依据指纹绑定的独立质量报告 |
| V033 | 自动任务质量检查策略 |
| V034 | 自动任务语句润色轮数与生成阶段上限 |
| V035 | 试写编辑报告、源样例与依据指纹、单次修订尝试 |
| V044 | 数据库全局风格目录、版本/启用/排序、11 种完整技法初始数据；项目仍保存独立 JSONB 快照 |
| V045 | 已发布人物完整底稿与规划关系快照、读者台账来源与版本内幂等标识；不迁移未来规划为正史 |
| V046 | 创作准备任务、当前确认指针、PREPARATION 台账来源、正文事实与计划关联；规划不写实际状态/关系/知识 |
| V047 | 原文解析任务、完整分段范围、逐字证据、作者逐项决定、确认与生成来源版本；不创建正史 |
| V048 | 校园关系：清爽叙事数据库风格预设 |
| V049 | 用户全局 Agent 提示词、不可变版本历史、Codex 会话提示词版本 |
| V050 | 正文来源改为可空合同关联与 writing_basis 写作依据 JSONB |
| V051 | 雪花规划输入快照、四阶段自由文本、状态与错误；候选规划不写正史 |

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
- 替换事件到达时先清理同章失效提交的投影；失效旧事件晚到时跳过。读路径独立按 `canon_commit.active` 过滤，不能依赖异步清理的先后顺序保证正确性。
- `ProjectionCheckpointStore`：按 `(event_id, projection_type)` 保证幂等。

约束：

- pgvector 和 Neo4j 必须使用不同 consumer group。
- 消费前检查 checkpoint，完成后再记录 checkpoint。
- 重复消息不能生成重复语义文档或图谱事实。
- 捕获异常后必须让 Kafka 感知失败，不能记录成功 checkpoint。
- 投影状态按 `PGVECTOR` 和 `NEO4J` 分别展示。

后续失败重放和全量重建设计见 `../../docs/NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`。

## 10. API 现状

当前 Controller：

| Controller | 主要能力 |
| --- | --- |
| `ProjectController` | 项目创建、列表、详情、创作意图 |
| `GlobalModelSettingsController` | 当前用户全局模型设置、乐观版本更新、ChatGPT 运行时模型与强度目录 |
| `AgentPromptController` | 当前用户全局提示词目录、版本保存、恢复默认、最近50版历史 |
| `AutomationController` | 章节范围任务创建、查询、继续、显式重试和取消 |
| `WritingStyleController` | 项目风格、预设、版本守卫的应用/清除、样本分析与上传、第一章试写和圣经风格推荐 |
| `StylePreviewEditingController` | POST writing-style/actions/check-preview 检查样例；POST writing-style/preview-reviews/{id}/actions/revise 按服务端报告选中问题修订 |
| `QualityReviewController` | 本章最新质量报告、生成检查、选中问题创建润色候选 |
| `StoryDirectionController` | 方向生成、读取、选择 |
| `StoryBibleController` | 故事圣经生成、编辑、发布 |
| `SnowflakePlanningController` | GET snowflake-plans/latest；当前作者项目四阶段候选文本、状态与错误 |
| `OutlineController` | 大纲生成、编辑、发布 |
| `PlanningCheckpointController` | 私有片段查询、执行、取消、重试及来源复核后复用 |
| `PlanningBatchController` | 从第一章的批次、一次一块、显式取消恢复、拼装既有大纲草稿；不自动发布 |
| `WritingController` | 合同、独立合同审阅、正文、正文审稿的生成与确认及审稿打回重写 |
| `CanonCommitController` | 正史提交、状态查询与同章正史替换 |
| `ProjectionStatusController` | Kafka、pgvector、Neo4j 投影状态 |
| `MemoryController` | 长期记忆召回预览 |
| `TypedCanonController` | 实体、状态、别名、提及、事件、伏笔、人物关系和知识边界查询 |

接口设计原则：

- 自动任务接口为 `/api/v1/projects/{projectId}/automation-runs`；创建必须携带 UUID `Idempotency-Key`，继续和取消分别使用 `/{id}/actions/resume`、`/{id}/actions/cancel`。
- `AutomationRunStore` 在短事务中锁定任务；`AutomationService` 在独立线程调用现有写作用例，禁止在模型调用期间持有任务事务。每次执行检查 attempt，旧执行者不能更新恢复后的任务。
- 自动任务只调度生成，不确认正文或审稿，不提交正史。每次继续重新判断来源版本；大纲变更需要取消旧任务后重建。
- 超过 20 分钟未更新的运行可显式恢复；取消在当前生成阶段完成后生效。已发生但未提交正史的导入章节等待人工登记。

- 路径统一以 `/api/v1` 开头。
- 项目资源必须验证当前用户所有权。
- 编辑使用 JPA `rowVersion` 或请求中的期望版本防止静默覆盖。
- 生成新候选应创建新版本；发布状态的内容不可原地编辑。
- 非法状态转换抛出明确业务错误，由 `ApiExceptionHandler` 转为统一响应。
- 合同及合同审阅写入接口统一返回 410，不调用模型、不保存产物。
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

`APP_CORS_ALLOWED_ORIGINS` 接受逗号分隔的精确页面来源；临时 SSH 转发调试可设为 `*` 允许所有 IP、域名和端口，修改后重启后端。WebConfig 没有启用跨域 Cookie 凭据；不要同时给通配符启用 allowCredentials。当前为固定开发身份，CORS 不替代登录鉴权，公开部署应收窄来源并保护访问。

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
- 已完成：规划、写作与导入的 Codex / DeepSeek 结构化调用收敛到 `StructuredModelGateway`；前端 JSON、FormData、204 和错误解析收敛到 `src/api/http.ts`。
- 已完成：为正史提交、Outbox 发布和两个投影消费者补充单元测试。
- 已完成：拆分写作合同/正文/审稿用例、提取写作上下文与项目访问校验、清理三处 Controller 数据访问；ArchitectureTest 检查 Controller/领域依赖、写作职责与依赖环以及关键事务声明，不增加依赖。
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
- 正文 Diff、正史恢复、全量影响分析和自主动态重规划；主动单元复核生成未来大纲草稿已见 V046。

每个阶段完成后再扩展下一阶段，避免同时铺开大量只有接口没有闭环的模块。

## 15. 已知技术债

- `CodexAppServerClient` 负责较多协议与进程细节，需要在补足回归测试后再拆，不要无测试重写。
- 写作用例已拆分，供应商调用生命周期已统一并拆分预算/会话策略，但公共网关和客户端仍位于 planning infrastructure；部分领域对象仍依赖 application 枚举、应用用例沿用 API DTO。本轮未进行全模块纯领域化或公共模型包迁移。重复项目归属校验已统一到 ProjectAccessService；锁定、来源复核、编辑版本和作者门禁仍由各业务用例管理，不统一成通用 CRUD。
- 新提交已开始物化类型化正史，但模型候选仍是扁平结构，旧 JSONB 事实尚未批量回填。
- 当前运行环境仍使用开发用特征哈希；正式适配器已完成，但未配置独立 Embedding 密钥和召回评测集。
- `AgentRun` 可记录供应商提供的真实 usage，缺失保持未知，旧字段仍为估算；自动任务缺任务级配置冻结、真实费用归集、即时中断与费用硬上限。
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
- 修改 Agent 阶段、Prompt、输入信息或输出 Schema：更新 `../../docs/NOVEL_AGENT_STAGES_AND_PROMPTS.md`。
- 引入新的配置或基础设施：更新“技术基线”和“配置”。
- 技术债解决或新增：更新“已知技术债”。
- 路线优先级改变：更新“近期开发路线”。
- 每次有效更新都修改“最后更新”日期。

不要把每日流水账、临时调试记录、真实连接信息或密钥写入本文件。它应保持为下一位开发者或 AI 能在几分钟内建立正确项目模型的高密度上下文。
## 17. 近期实现（2026-09-28）

- V031：`automation_run` 保存章节范围、当前章节、来源大纲、模型、状态、执行次数、取消请求及 JSONB 阶段产物；项目未结束任务和创建幂等键均有唯一索引。状态为 PENDING、RUNNING、WAITING_FOR_USER、FAILED、CANCELLED、SUCCEEDED。
- V033：`automation_run.quality_review_enabled` 持久化质量检查策略，默认 false 保持旧调用/旧任务行为；QUALITY_REVIEW 步骤复用 QualityReviewService，无额外模型 Prompt 或事实审批能力。检查失败重试复用保存稿，取消在当前检查完成后停止，正文/风格/修订变化后继续任务会重检。任务界面可跳入当前章对应编辑页并沿用任务模型。
- V034：新增 max_auto_revision_rounds 与 max_generation_steps；QUALITY_REVISION 步骤复用质量建议修订，不自动确认或改正史。所有已开始步骤均计生成额度，当前章已开始修订计轮数；持久化锁内检查额度再记录步骤，失败重试/超时恢复不清零。新稿需复检，INFO / FLUENCY 标签仅用于调度，真实文学效果与事实保留尚未证明。
- V032：`quality_review_version` 保存不可变报告、来源正文 ID/行版本、大纲 ID 与检查依据 SHA-256；正文渲染内容、人物档案、风格和正史水位进入指纹。模型调用在事务外，保存时锁定并刷新项目/正文，再核对来源；旧稿/依据已改变则拒绝保存或润色。修订必须经原有作者确认和正史审稿链路。
- 新运行阶段 `QUALITY_REVIEW` 使用独立 Graph 和输出校验，召回复用 CHAPTER_REVIEW 的只读工具白名单与预算；`STYLE_ANALYSIS` 只分析样本文本、不召回故事记忆。两阶段均不复用 Codex 旧线程。样本本身不保存为导入作品，但真实模型 Prompt 按现有私有任务记录规则留存，禁止输出到日志。
- `STYLE_PREVIEW` 独立 Graph 使用候选风格、所选大纲第一章、关联圣经和人物档案；不读取已应用项目风格、不召回后续章节记忆、不复用正式正文线程，试写正文限制 6000 字符。表达服从人物/视角/事实约束；目标字数为模型参考，不作精确字数保证，文学效果未评测。
- `STYLE_PREVIEW_REVIEW` 输出复用 QualityReviewContent，严格核验证据在源 body 中；`STYLE_PREVIEW_REVISION` 输出复用 title/body Schema。StylePreviewEditingService 不开启长事务，StylePreviewReviewStore 负责权限、短读取、锁定保存/认领及返回前来源复核。指纹包括大纲 ID/版本、圣经 ID/版本/内容、卷章、人物档案、当前姓名渲染、候选风格指南及完整样例；不依赖已应用风格。报告保存样例仅作编辑依据，不是 manuscript_version，也没有正史审批职责。现无历史列表/恢复 API，样例/Prompt 按私有手稿保护，不输出日志。
- LOCAL_TEMPLATE 的质量报告评分为 null，仅检测重复段落、连接词堆积与连续标点；样本分析只统计句式指标。模板修订只用于版本流程验证，不承诺语义改写与文学质量。
- 自动任务验证：领域状态机、版本关联决策、编排服务和 Controller 单元测试；`NOVEL_AUTOMATION_DB_TEST=true` 时运行 `AutomationPersistenceTest`，验证真实 PostgreSQL、幂等/权限和本地模板两章完整链路，使用独立随机 Schema 并在结束后清理；测试关闭 Kafka 消费并替换 Outbox 发布与 Embedding 定时回填。

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
