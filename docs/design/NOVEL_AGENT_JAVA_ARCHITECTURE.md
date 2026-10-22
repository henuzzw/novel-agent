# Java 服务端当前架构

最后更新：2026-10-22（模型接入模块与调用端口边界）。

每个模块根包的 `package-info.java` 写有该模块的职责、接口入口、核心服务和主要依赖。

本文描述已实现的职责划分。业务能力与后续路线仍以 `apps/server/AGENTS.md` 和自动化待办为准。

## 1. 模块边界

保留单个 Spring Boot 应用，按业务模块组织，没有新增 Maven 模块、框架或第三方依赖。

| 模块 | 负责内容 |
| --- | --- |
| project | 项目、当前作者、创作意图、项目访问权限 |
| prompt | 全局阶段提示词、默认目录、用户配置与版本历史 |
| planning | 雪花规划、确认资料快照、大纲及阶段生成适配 |
| writing | 正文版本、审稿与质量检查、风格、自动编辑循环；合同仅保留历史读取与退役接口 |
| writing 风格试写 | WritingStylePreviewService 读取同项目已保存大纲第一章，WritingGenerationWorkflow 的独立 STYLE_PREVIEW Graph 生成临时样例；不通过 ManuscriptService 保存、不修改风格和正史 |
| writing 试写编辑 | StylePreviewEditingController → StylePreviewEditingService → Workflow 的独立检查/修订 Graph；StylePreviewReviewStore 保存依据与报告、锁内单次认领、生成后复核，不回调写作门面 |
| writing 风格推荐 | WritingStyleRecommendationService 复用 ConfirmedSnowflakeService 的权限与版本读取，独立 STYLE_RECOMMENDATION Graph 校验规范预设及资料证据；前后校验源快照、不应用风格或改变权威数据 |
| agent | 自动任务、阶段决策、只读工具、模型运行记录 |
| memory | 记忆召回、Token 预算、Embedding、记忆预览 |
| canon | 正史提交、类型化事实、人物资料、Outbox、投影 |
| ingest | 作品导入、解析、导入反推规划 |
| chat | 项目创作对话与修改候选 |
| modelaccess | 公共模型调用端口、供应商执行器、输出协议、预算、目录、认证与会话 |
| platform | HTTP 响应工具、异常映射、请求日志，以及 Json、Sha256、Text、GraphStateCodec 等通用工具 |

各模块沿用 `api / application / domain / infrastructure` 分层：

- `api` 处理 HTTP 参数、响应、状态码及缓存策略；Controller 不访问数据库。ETag、`If-Match`、204/404 等响应细节统一使用 `platform.api.ApiResponses`。
- `api.dto` 存放请求与响应对象（`*Request`、`*Response` 及少量接口视图记录），即 HTTP 接口契约。
- `application` 编排用例、检查权限及决定事务边界；沿用现有响应 DTO，不强制增加一层通用转换框架。
- `domain` 负责状态转换与业务约束，不依赖 Controller 或存储适配器；现阶段仍允许 JPA 映射及既有应用层枚举。
- `infrastructure` 封装存储、模型协议、SQL、消息及外部接口。

### 格式与业务注释

Controller 与 application Service 使用四空格缩进；在项目根目录运行 `powershell -NoProfile -File scripts/format-server.ps1` 整理格式，附加 `-Check` 可只检查。脚本固定格式器版本，首次使用下载到系统临时缓存；不调整请求映射、事务注解、import 或字符串内容。

类注释描述职责与层间边界，公开业务方法说明具体读写及副作用，并按实际行为注明幂等、来源版本、后台执行和作者确认条件。参数应区分章节号、稳定记录 ID、行版本和正史水位。简单构造器与访问器无需机械注释；已有退役入口明确返回 410，记忆整理与正文发布分别描述。当前公开业务方法已补齐职责注释，新增方法应沿用此约定。

### 请求类型复用

- `ContentUpdateRequest<T>` 仅统一 HTTP 的 `{content: ...}` 包装，替代 UpdateOutlineRequest、UpdateManuscriptRequest、UpdateChapterReviewRequest。Controller 用具体泛型声明保证 Jackson 绑定为对应领域内容，应用服务继续接收原有具体内容类型。大纲接口保留 `@Valid` 与 content 必填校验；正文/审稿的既有校验及审稿批准的可选 body 不变。
- 规划批次和规划片段共用 `planning.api.dto.VersionRequest`，统一 `{version: ...}` 与必填、非负约束，版本冲突仍由各用例判断。
- 不同内容、来源、状态和作者授权仍使用专用类型；不为统一命名增加通用响应基类，也不向响应添加无关的可空字段。ContentUpdateControllerTest 验证三种具体内容绑定、ETag、大纲必填和审稿批准可选 body；规划 Controller 测试验证共用版本请求。

### 模型调用边界

业务生成器依赖 `modelaccess.application.StructuredModelGateway` 接口，提供 Prompt、Schema、
解析与保存回调以及会话策略。`ModelProvider` 与 `CodexSessionPolicy` 位于 `modelaccess.domain`，
不再依赖规划模块。自由文本请求通过 `FreeTextModelRequest` 复用相同调用端口。

供应商客户端、输出协议与会话实现集中到 `modelaccess.infrastructure`：

```text
规划 / 写作 / 导入 / 对话 / 书名生成
    -> StructuredModelGateway（application 接口）
        <- RoutedStructuredModelGateway（infrastructure 实现）
            -> ModelPromptAssembler：用户提示词、边界、阶段规则与输出协议
                -> StagePromptContributor（application 扩展接口）
                    <- ImportAnalysisPromptContributor（ingest）
                    <- OutlinePromptContributor（planning）
            -> AbstractModelExecutor：冻结参数、预算、记录、解码、业务保存、成功确认
                <- LegacyCodexExecutor / ProjectCodexExecutor / ChatGptHttpExecutor / DeepSeekExecutor
                    -> 各供应商客户端、会话与资料策略
```

导入与大纲的附加规则由所属业务模块实现 `StagePromptContributor`，按声明顺序注入；
保留原有规则条件、文本及拼接顺序。增加业务阶段规则不再让模型模块引用业务实现。
模型目录读取及配置组合校验归属 `modelaccess.application.GlobalModelCatalogService`，
项目模块继续保存全局设置，HTTP 路由与响应结构不变。

原有方法签名、提示词、Schema、令牌上限、保存回调与失败传播保持兼容。
只有业务保存成功后才确认会话资料已交付；鉴权或普通网络错误不自动切换供应商。
迁移保留实体名称、表名、枚举字符串、会话键与配置键，不需要数据库迁移。
客户端日志分类随包名同步更新，仍使用 `CODEX_LOG_LEVEL`。

前端 API 统一经过 `src/api/http.ts`，集中处理 `no-store`、JSON、FormData、204 与错误。
状态管理使用 Vue 响应式与 TanStack Query，已移除 Pinia。

## 2. 写作职责

```text
WritingController / AutomationService
    -> WritingService（兼容入口，只委托）
        -> ChapterContractService
        -> ManuscriptService
        -> ChapterReviewService -> ManuscriptService（生成返工候选）

QualityReviewService -> ManuscriptService（生成润色候选）
                    -> QualityReviewStore（校验来源并保存）

活动写作用例 -> WritingContextService
           -> WritingGenerationWorkflow -> WritingGenerationGateway
ChapterContractService -> 历史读取；退役写入统一返回 410
```

| 类 | 职责 |
| --- | --- |
| WritingService | 兼容公共入口，委托专用服务；不注入 Repository，不持有事务 |
| ChapterContractService | 历史版本读取；旧写入先检查权限，再统一返回 410，不调用模型 |
| ManuscriptService | 正文生成与基准版本、编辑、人工修订、作者确认、渲染导出、准备质量修订候选 |
| ChapterReviewService | 已确认正文审稿、候选事实决策、审稿确认、打回生成及原子保存 |
| WritingContextService | 按已发布大纲查找卷章和圣经，检查访问权限，组装记忆召回输入 |
| ResourceVersionConflictException / Text | 复用行版本校验与文本规范化；状态转换仍由领域对象负责 |
| QualityReviewService / Store | 保持原有生成与持久化分离，不再反向依赖写作门面 |

专用服务不回调 `WritingService`。其他模块可继续使用兼容入口，不需要感知内部拆分。

### 退役链路与复用

- 合同写接口保留原有五条 URL 和 HTTP 方法，Controller 合并为 POST/PUT 两个兼容处理方法，门面与合同服务各保留一个拒绝入口。先校验项目归属，再返回 410；不再绑定旧请求体或解析 If-Match，因此空请求、旧 JSON、格式错误的旧参数均按退役入口处理。删除 UpdateChapterContractRequest / UpdateChapterContractReviewRequest 和五组无效写方法；历史读取及其响应 DTO、ETag 保留。
- 合同生成与合同审阅已无活动用例调用，删除对应 Graph、生成节点、输出校验、网关方法、动态 Prompt、Schema、Parser 和本地合同模板。启动不再编译这两张退役图。
- `WritingGenerationWorkflow` 的正文、风格推荐、试写及试写编辑复用基础输入校验；各阶段特有的章节、字数和逐字证据检查保留。
- `WritingOutputSchemas` 共用问题字段构造，审稿仍允许 BLOCKING 和自由类别，质量检查仍只允许 WARNING/INFO 与四个质量维度；输出字段与顺序不变。
- 前端删除无人使用的合同 API 函数及版本类型；公共 HTTP 入口、正文修订、版本冲突与请求状态处理不变。
- `ChapterContractContent` 仍被写作依据使用，不能连同退役生成器一并删除。本次不删除数据库表、历史项目数据或已保存提示词，不改变模型预算、活动 Prompt 或作者确认权限。

## 3. 查询与权限

```text
AgentRunController -> AgentRunQueryService -> AgentRunQueryRepository -> JDBC
ProjectionStatusController -> ProjectionStatusService -> ProjectionStatusRepository -> JDBC
MemoryController -> MemoryPreviewService -> 预算与只读记忆召回

以上查询服务及 WritingContextService -> ProjectAccessService
```

- `ProjectAccessService.requireOwnedProject` 统一作者权限校验；不存在与不属于当前作者的项目均为原有 `ProjectNotFoundException`。
- 查询仓库只封装 SQL，不承担授权；查询服务必须先验证项目再调用仓库。
- 项目、方向、圣经、大纲、人物补全、导入、自动任务、正史提交、人物命名、人物档案、正史查询、风格及质量报告的 13 处重复归属校验已统一接入。服务仅因实际创建、列表或保存需要保留项目 Repository / 当前作者依赖，不为权限校验重复注入。
- 保存用例仍自行掌握事务、悲观锁及来源版本复核；`requireOwnedProject(NovelProject)` 可在锁后 refresh 时重新检查已加载实体，不发起额外查询。权限读取不冻结后续模型调用依据，也不替代业务版本校验。
- Agent Run 的列表限制、排序、估算汇总和按项目查询 Prompt 的 SQL 保持不变。
- 完整 Prompt 仍只按需读取，成功响应保持 `Cache-Control: no-store`，缺失运行记录返回 404。
- 投影状态字段、记忆预览参数默认值及全部业务 API 地址保持不变。

### 人物档案存储边界

人物档案采用明确的应用层端口，将业务判定与 JDBC 分开：

```text
CharacterProfileController / 写作上下文
    -> CharacterProfileService：权限、事务、版本冲突、引用判定与上下文组装
        -> CharacterProfileStore（application 接口，不依赖 Spring / JDBC）
            <- JdbcCharacterProfileStore：SQL、有效人物/项目过滤、结果映射
```

服务控制幂等补空和版本更新的执行顺序；适配器只返回更新行数，服务据此读取当前版本并抛出原有版本冲突。
list 的补空与 update 的补空、条件更新和回读仍位于原有服务写事务内，不拆成独立提交。
完整名称、角色键、稳定 ID 和别名的引用解析、同名歧义及自由叙述的保守回退仍由服务决定，
SQL 不推断参与人物，也不解除作者侧秘密与视角知识的区分。HTTP DTO 和 Prompt 文本保持兼容。

### 类型化正史事实策略

事件、状态、关系、知识、伏笔的规则分别涉及不同实体与表结构，采用 `CanonFactProjector` 策略接口：

```text
正史提交 / 已发布记忆用例（原有事务）
    -> TypedCanonMaterializer：主体解析、通用事实、按类型分派、批次完成后同步规划
        -> CanonFactStore <- JdbcCanonFactStore：通用事实写入、历史退役
        -> EntityResolutionService：消歧优先级、歧义与代词拒绝
            -> EntityResolutionStore <- JdbcEntityResolutionStore：查询、幂等创建、出现记录 upsert
        -> CanonFactProjector
            <- EventFactProjector
            <- StateFactProjector
            <- RelationshipFactProjector
            <- KnowledgeFactProjector
            <- ForeshadowFactProjector
```

策略声明支持的类型别名、是否需要主体实体，以及投影写入和历史退役。协调器通过 Spring 注入的策略列表建立只读注册表，
统一规范化类型并拒绝重复注册；增加类型的投影实现和对应表迁移时，不再扩展协调器的分派 switch 或退役表清单。
每个策略承载实际规则和 SQL，没有增加只转发调用的工厂或通用父类。

未知类型仍保存通用 `story_fact`，不强行选择近似策略；未知状态字段、旧类型别名、关系自环及默认字段值保持原有行为。
每条通用事实先保存再投影，策略异常直接传播，所有操作参与调用方原有事务；退役每个策略只调用一次，不按别名重复调用。
没有新增事务传播、后台任务、付费模型调用或装饰器。当前附加逻辑无需独立组合，保留已有模型执行器的模板方法结构。

协调器与消歧应用服务均不直接使用 JDBC、SQL 或存储适配器类型。存储端口返回候选与持久化结果，
业务判断留在服务中：请求 ID 必须匹配当前项目与类型，标准名优先于别名，多匹配时拒绝自动选择，
未解析代词不能创建实体。出现记录仍按来源、候选编号和角色更新，修订不追加重复行。
实体创建、出现记录、通用事实及类型化投影共用调用方事务；失败时全部回滚。

### 公共指纹

`platform.support.Sha256` 只依赖 JDK，提供 UTF-8 文本和原始字节的 SHA-256 小写十六进制编码。
12 个类、13 处原有算法实现统一复用它，覆盖任务请求、记忆上下文、导入、规划批次与分块、
质量/试写报告、前三章复核、全书扫描和伏笔承诺台账。

序列化及“哪些字段构成依据”仍由各业务模块决定。公共工具不排序 JSON、裁剪空白、
归一化换行或改变 null 规则；原有请求快照的 null 转空串仍在原调用方执行。
每次调用使用独立 MessageDigest，防止并发请求共享可变算法状态。
既有数据库中的指纹仍然有效，不需要数据回填或迁移。

## 4. 事务规则

| 操作 | 边界 |
| --- | --- |
| 合同 / 正文 / 审稿查询 | 专用服务方法 `@Transactional(readOnly = true)` |
| 作者编辑、确认、人工创建修订 | 专用服务方法 `@Transactional` |
| 上下文读取 | `WritingContextService.context` 短只读事务 |
| 模型生成、质量修订、审稿打回 | 入口不打开长事务；模型调用结束后再保存 |
| 审稿打回成功落库 | 原有 `writingTransactionTemplate` 同时保存新草稿与 RETURNED 审稿 |
| 质量检查 / 修订落库 | 原有 QualityReviewStore 锁定项目 / 正文、复核来源与依据后保存 |
| 试写检查 / 修订 | StylePreviewReviewStore 短事务保存报告或锁内认领一次尝试；外部模型在事务外执行，修订返回前复核上下文，候选不保存为正式正文 |
| 运行记录 / 投影状态查询 | 用例服务短只读事务 |
| 记忆预览 | 权限读取结束后进行召回，不用整体数据库事务包裹外部访问 |

事务声明放在实际被 Spring 调用的专用 Bean 上，不依赖门面或同类自调用生效。正文作者确认、审稿事实选择和正史提交规则不变。

本轮未改变普通生成结束后的并发来源校验能力；不要据此宣称所有模型生成已经具备质量报告同等级的来源锁定。

## 5. 架构回归

`ArchitectureTest` 使用已有 Spring Core 的 ASM 读取编译后字节码，不增加测试依赖。当前检查：

1. Controller 不直接引用存储适配器、JDBC 或 JPA 访问 API，包括方法体内访问。
2. domain 不引用本项目 api 或 infrastructure。
3. 业务归属校验只通过 ProjectAccessService；NovelProject.getOwnerId 只允许该策略调用。
4. 规划生成器、导入网关和写作路由依赖统一模型调用端口，不直接依赖供应商客户端、会话仓库或运行记录器。
5. modelaccess 之外的业务代码不引用 modelaccess.infrastructure。
6. modelaccess 不反向引用 planning、ingest、writing 或 canon 的类。
7. 模型调用端口、阶段规则端口及公共枚举不依赖 Spring、JPA、HTTP DTO 或基础设施实现。
8. 写作 application 类之间无直接字节码依赖环；嵌套类归并到所属顶层类。
9. 写作与质量的模型生成入口不声明长数据库事务。
10. 作者编辑和确认入口保留写事务声明。

该测试不是完整 Spring Bean 图验证，也不覆盖反射、运行时动态依赖或所有跨模块循环。真实 Spring 上下文与 PostgreSQL 回归用于补充验证装配和事务行为。

运行：

```powershell
cd apps/server
.\mvnw.cmd -Dtest=ArchitectureTest,ModelExecutorWiringTest,StructuredModelGatewayTest test
```

2026-10-07 本轮验证：完整测试 598 项，默认 548 通过、50 环境条件跳过；
另外启用四个隔离数据库测试类，49 项全部通过，合计 597 通过，
1 项真实模型质量评测因未启用仍跳过，无失败。
覆盖权限隔离、锁后复核、规划资料发布同步、正史原子提交、来源指纹、
提示词保存与实际网关记录、会话恢复/版本轮转/失败回写及流式输出回调。
测试没有使用付费模型，也没有修改现有小说数据。本轮没有新增迁移或依赖。
当前正在运行的后端未自动重启，需要重启后才加载新实现。

隔离数据库测试沿用 `NOVEL_DB_TEST=true` 和已有数据库配置；使用随机测试 Schema，关闭消息消费并替换后台发布 / 回填，结束时只清理该 Schema。新增验证审稿保存失败的原子回滚及运行查询权限，既有作者门禁与有限润色链路继续覆盖。

## 6. 后续优化

退休流程的默认指令也已清理：合同/合同审阅、独立人物补全的硬编码分支，以及独立圣经/导入反推圣经的三份资源不再随应用加载。活动模板、默认发布号和历史配置记录保持原样；历史合同查询和返回 410 的兼容接口保留，雪花资料结构与转换仍有实际用途。

已清除没有生产调用的提示词别名、旧项目模型设置转换、创作准备人物初始化、人物蓝图副本和传输判定辅助方法；不可达的创作准备默认指令分支同步清除。活动模板资源与兼容接口保留。判断可删除代码时，应区分显式调用、Spring 装配/回调、配置绑定及历史序列化，不能只依据文本引用数量。

- `CodexAppServerClient` 的进程、JSON-RPC 和 turn 生命周期仍集中，需独立协议测试后按职责拆分。
- 公共模型能力已迁入 modelaccess；模块仍读取项目配置、提示词并记录 AgentRun，尚未独立部署为服务。
- 部分领域对象仍引用 application 枚举，且应用层使用 API DTO；进一步解耦应另行评估迁移成本。
- 人物资料、创作准备等服务的复杂 SQL 与业务编排还可继续按真实职责拆分；不引入万能 CRUD 基类，不混用读取、锁定、作者确认和正史状态规则。
- 普通生成的并发版本 / 来源校验、长期调用即时取消和真实 usage 仍属于后续业务完善，不混入纯架构调整。
