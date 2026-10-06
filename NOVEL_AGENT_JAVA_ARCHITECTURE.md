# Java 服务端当前架构

最后更新：2026-10-05。

本文描述已实现的职责划分。业务能力与后续路线仍以 `apps/server/AGENTS.md` 和自动化待办为准。

## 1. 模块边界

保留单个 Spring Boot 应用，按业务模块组织，没有新增 Maven 模块、框架或第三方依赖。

| 模块 | 负责内容 |
| --- | --- |
| project | 项目、当前作者、创作意图、项目访问权限 |
| planning | 故事方向、圣经、大纲及相关模型适配 |
| writing | 合同、合同审阅、正文版本、正史审稿、质量检查、风格 |
| writing 风格试写 | WritingStylePreviewService 读取同项目已保存大纲第一章，WritingGenerationWorkflow 的独立 STYLE_PREVIEW Graph 生成临时样例；不通过 ManuscriptService 保存、不修改风格和正史 |
| writing 试写编辑 | StylePreviewEditingController → StylePreviewEditingService → Workflow 的独立检查/修订 Graph；StylePreviewReviewStore 保存依据与报告、锁内单次认领、生成后复核，不回调写作门面 |
| writing 风格推荐 | WritingStyleRecommendationService 复用 StoryBibleService 的权限与版本读取，独立 STYLE_RECOMMENDATION Graph 校验规范预设及圣经证据；前后校验源快照、不应用风格或改变权威数据 |
| agent | 自动任务、阶段决策、只读工具、模型运行记录 |
| memory | 记忆召回、Token 预算、Embedding、记忆预览 |
| canon | 正史提交、类型化事实、人物资料、Outbox、投影 |
| ingest | 作品导入、解析、导入反推规划 |
| platform | HTTP 配置、异常映射等横切能力 |

各模块沿用 `api / application / domain / infrastructure` 分层：

- `api` 处理 HTTP 参数、响应、状态码及缓存策略；Controller 不访问数据库。
- `application` 编排用例、检查权限及决定事务边界；沿用现有响应 DTO，不强制增加一层通用转换框架。
- `domain` 负责状态转换与业务约束，不依赖 Controller 或存储适配器；现阶段仍允许 JPA 映射及既有应用层枚举。
- `infrastructure` 封装存储、模型协议、SQL、消息及外部接口。

结构化模型调用统一经过 `StructuredModelGateway`。规划、写作和导入生成器只负责选择
Prompt、Schema、解析器及 Codex 会话策略；公共网关负责 Codex / DeepSeek 路由、
`AgentRun` 记录、Codex thread 新建或恢复、失效 thread 重建和 turn 回写。新增模型阶段不得
在业务生成器中再次复制这套生命周期。前端 API 则统一经过 `src/api/http.ts`，集中处理
`no-store`、JSON 请求头、FormData 边界、204 响应和 Problem Details 错误。

## 2. 写作职责

```text
WritingController / AutomationService
    -> WritingService（兼容入口，只委托）
        -> ChapterContractService
        -> ManuscriptService
        -> ChapterReviewService -> ManuscriptService（生成返工候选）

QualityReviewService -> ManuscriptService（生成润色候选）
                    -> QualityReviewStore（校验来源并保存）

三个写作用例服务 -> WritingContextService
                -> WritingGenerationWorkflow -> WritingGenerationGateway
```

| 类 | 职责 |
| --- | --- |
| WritingService | 保留原有 22 个公共业务方法，委托专用服务；不注入 Repository，不持有事务 |
| ChapterContractService | 合同生成、版本读取、编辑、独立审阅、审阅确认与合同确认 |
| ManuscriptService | 正文生成与基准版本、编辑、人工修订、作者确认、渲染导出、准备质量修订候选 |
| ChapterReviewService | 已确认正文审稿、候选事实决策、审稿确认、打回生成及原子保存 |
| WritingContextService | 按已发布大纲查找卷章和圣经，检查访问权限，组装记忆召回输入 |
| WritingChecks | 写作内部版本前置校验及要求文本规范化；不承担状态机 |
| QualityReviewService / Store | 保持原有生成与持久化分离，不再反向依赖写作门面 |

专用服务不回调 `WritingService`。其他模块可继续使用兼容入口，不需要感知内部拆分。

## 3. 查询与权限

```text
AgentRunController -> AgentRunQueryService -> AgentRunQueryRepository -> JDBC
ProjectionStatusController -> ProjectionStatusService -> ProjectionStatusRepository -> JDBC
MemoryController -> MemoryPreviewService -> 预算与只读记忆召回

以上查询服务及 WritingContextService -> ProjectAccessService
```

- `ProjectAccessService.requireOwnedProject` 统一这些用例的作者权限校验；不存在与不属于当前作者的项目均为原有 `ProjectNotFoundException`。
- 查询仓库只封装 SQL，不承担授权；查询服务必须先验证项目再调用仓库。
- 原有其他业务服务的权限检查暂时保留，未一次性改动所有领域服务。
- Agent Run 的列表限制、排序、估算汇总和按项目查询 Prompt 的 SQL 保持不变。
- 完整 Prompt 仍只按需读取，成功响应保持 `Cache-Control: no-store`，缺失运行记录返回 404。
- 投影状态字段、记忆预览参数默认值及全部业务 API 地址保持不变。

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
3. WritingService 仅依赖三个专用写作服务，无自身事务声明。
4. 写作内部不反向依赖门面。
5. 写作 application 类之间无直接字节码依赖环；嵌套类归并到所属顶层类。
6. 写作与质量的模型生成入口不声明长数据库事务。
7. 作者编辑和确认入口保留写事务声明。
8. 规划生成器、导入网关和写作路由只依赖统一结构化模型网关，不直接依赖供应商客户端、Codex 会话仓库或运行记录器。

该测试不是完整 Spring Bean 图验证，也不覆盖反射、运行时动态依赖或所有跨模块循环。真实 Spring 上下文与 PostgreSQL 回归用于补充验证装配和事务行为。

运行：

```powershell
cd apps/server
.\mvnw.cmd test
```

隔离数据库测试沿用 `NOVEL_AUTOMATION_DB_TEST=true` 和已有数据库配置；使用随机测试 Schema，关闭消息消费并替换后台发布 / 回填，结束时只清理该 Schema。新增验证审稿保存失败的原子回滚及运行查询权限，既有作者门禁与有限润色链路继续覆盖。

## 6. 后续优化

- `CodexAppServerClient` 的进程、JSON-RPC 和 turn 生命周期仍集中，需独立协议测试后按职责拆分。
- 通用模型网关及底层客户端暂时位于 `planning.infrastructure`；若供应商继续增加，再迁入独立
  platform / model infrastructure 包。本轮为控制改动面，不做纯包移动。
- 部分领域对象仍引用 application 枚举，且应用层使用 API DTO；进一步解耦应另行评估迁移成本。
- 可以逐步让其余业务用例复用 ProjectAccessService，但权限规则不能因机械替换改变。
- 普通生成的并发版本 / 来源校验、长期调用即时取消和真实 usage 仍属于后续业务完善，不混入纯架构调整。
