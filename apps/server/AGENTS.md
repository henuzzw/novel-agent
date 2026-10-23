# Novel Agent 服务端开发上下文

> 本文件是服务端面向 AI 编程助手和开发者的持续维护文档。它描述当前代码，而不是只描述理想架构。进入 `apps/server` 开发前先阅读本文件；完成后端功能、迁移、接口或架构调整后，必须同步更新相关章节。

## 1. 项目目标

Novel Agent 是面向长篇小说作者的可控创作系统。服务端负责：

- 管理项目、创作意图、雪花九步、已确认规划资料和分层大纲；不再提供独立故事方向或圣经生成/选择/编辑/发布阶段。
- 依据已发布大纲生成单份正文草稿、质量检查和审稿报告，不再生成或批准章节合同。
- 让作者决定哪些正文和候选事实进入正史。
- 使用 PostgreSQL 保存权威业务数据和正史版本。
- 使用 pgvector 召回历史正文与摘要。
- 使用 Neo4j 保存可重建的图谱投影；当前相关正史事实召回直接查询 PostgreSQL。
- 使用 Kafka + Outbox 异步更新可重建投影。
- 对 Codex、DeepSeek 和本地模板提供统一的领域生成入口。

系统不允许模型直接写正史。模型只能生成候选，所有高风险变更必须经过服务端校验和作者确认。

雪花九步当前采用大爽文取向（2026-10-09）：`snowflake_planning.txt`阶段规范、`snowflake_system.txt`专用系统角色及`SnowflakeStep`动态指令共同要求主动破局、可信反转、收益积累和终局兑现。第7步仍复用统一人物设计，不新增调用。AgentPromptService按key解析默认系统角色，SNOWFLAKE_PLANNING专用，其余通用；自定义配置优先。默认发布号`snowflake-commercial-2026-10-09`。只影响后续生成，不改历史项目，KEEP/授权/续写事实边界与纯文本输出不变。

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
| 模型 | Codex App Server、SIWC ChatGPT OAuth Responses SSE、DeepSeek Responses、本地模板 |
| 测试 | JUnit 5、Spring Boot Test |

Maven Wrapper 位于本目录。不要假设机器安装了全局 Maven。

## 3. 权威文档顺序

发生描述冲突时，按以下顺序判断：

1. 当前代码和 Flyway 迁移：代表实际运行状态。
2. 本文件：代表当前开发上下文和近期路线。
3. `../../docs/README.md`：代表整个项目的能力概览。
4. `docs/` 各设计文档：代表完整目标和长期方案。

关键设计文档：

- `../../docs/design/NOVEL_AGENT_SYSTEM_DESIGN.md`
- `../../docs/design/NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`
- `../../docs/design/NOVEL_AGENT_DOMAIN_MODEL.md`
- `../../docs/design/NOVEL_AGENT_WORKFLOW_DESIGN.md`
- `../../docs/design/NOVEL_AGENT_AI_SPEC.md`
- `../../docs/design/NOVEL_AGENT_STAGES_AND_PROMPTS.md`
- `../../docs/design/NOVEL_AGENT_AGENT_TOPOLOGY.md`：历史 23 种模型工作流图，当前简化图见 `../../docs/design/NOVEL_AGENT_SIMPLIFIED_WORKFLOW.md`。
- `../../docs/design/NOVEL_AGENT_API_DESIGN.md`
- `../../docs/design/NOVEL_AGENT_TEST_PLAN.md`

设计文档中的接口和表不一定已经实现。开发前必须通过 Controller、Repository 和迁移文件确认现状。

## 4. 当前完成度

最后更新：2026-10-22。

### 4.1 已完成

- 模型模块边界：公共网关、四个执行器、输出协议、预算和 Codex 会话/客户端归入 modelaccess。业务通过 application.StructuredModelGateway 调用；ModelProvider / CodexSessionPolicy 位于 modelaccess.domain。FreeTextModelRequest 与 GlobalModelCatalogService 归属模型应用层。导入、规划规则分别实现 StagePromptContributor，模型模块不引用这些业务实现。现有调用方法、提示词文本与顺序、表名、枚举值、会话键、保存回调和失败传播保持兼容；架构测试约束跨模块依赖，装配测试覆盖接口注入与规则顺序。

- V064 记忆修订实测修复（2026-10-14）：实体出现记录按来源／编号／角色更新；story_fact唯一键改为保留历史版本且仅一条当前有效记录，避免整批重物化重复键。状态写入使用clock_timestamp区分同事务内的连续变化，记忆指令强调章末返回／归还。Neo4j投影将Numeric置信度转换为double。新增真实PostgreSQL版本唯一性与出现记录重物化验证；定向测试通过，21:21已重启加载V064。项目9f843969专项补救：22章记忆成功、234条引文可核对、发布正文哈希未变、22章完整导出；正文第3／15／19章仅另存未发布修订候选，逐章正文对前文记忆复核见 ../../docs/samples/PROJECT_9f843969_MEMORY_RECOVERY.md。

- V063 自动记忆与作者管理（2026-10-14）：CHAPTER_REVIEW只从已发布本章提取，附原写作计划的视角定位及实体ID目录，不将计划当事实。证据与引用校验后整批ACCEPTED/落库，不再以部分NEEDS_CONFIRMATION结束；失败整批回滚、FAILED保留有效候选供编辑，未知代词和跨项目ID不能强绑。物品/地点可描述命名，不要求名称逐字在引文中。作者PUT memory可编辑/新增/移除，保留行版本守卫与原文证据，V063历史表记录旧快照；更新退役旧事实/派生记录、提升索引水位并重建投影，不改正文。自动推进开启记忆时只在前文SUCCEEDED后继续，前文失败/待处理/缺失阻塞；新A请求前再次守卫，已有正文检查和表达修订仍可执行。旧章记忆修改后阻止默认沿用受影响旧后文继续生成。相关事实走PostgreSQL权威版本与章节过滤，不依赖Kafka/Neo4j延迟，历史请求不读新版accepted_facts快照。向量正文保留原发布版本，内容未变保留已有嵌入；存量不自动回填或重跑。17:47（UTC+8）已重启并应用V063，健康检查及新历史接口正常，后端49项及前端5项定向测试通过；详见 ../../docs/features/NOVEL_AGENT_PUBLISHED_MEMORY.md。

- 本机小说 Fast 已关闭（2026-10-13）：两个独立 Codex runtime/home/config.toml 移除 service_tier=fast，并设置 features.fast_mode=false；原生配置读取及独立测试线程的新建/恢复均为无priority，0生成轮次。20:08（Asia/Singapore）确认第7至13章范围成功结束、全部任务/记忆/租约空闲后重启8081，健康UP、5173及模型配置代理200。当前保存模型gpt-6.1-sol/high、版本6，本次未改模型设置，桌面 Codex 不受影响；未停止或重跑任务、未改小说数据。详见 ../../docs/design/NOVEL_AGENT_MODEL_EXECUTION.md，下方10月12日启用记录为历史状态。

- 项目Token统计（2026-10-13）：新增GET agent-runs/usage，先校验项目归属，以UTC截止时刻和只读REPEATABLE_READ快照复用现有汇总；返回全项目和按started_at滚动最近5小时的两期统计，不受最近100条限制。实际/估算分开累计，缺失实际用量显示覆盖缺口，失败/停止已记录用量计入；不代表账号额度。任务页每15秒刷新、支持手动刷新及错误状态。11项后端定向测试（含PostgreSQL临时表）、4项前端测试、类型与相关ESLint通过，无迁移/依赖/模型调用，需重启；详见 ../../docs/features/NOVEL_AGENT_TOKEN_USAGE.md。

部署记录（2026-10-13 19:12，Asia/Singapore）：作者授权后核实全部任务和会话租约空闲并重启8081，项目Token统计已加载；健康UP、5173代理usage接口200且no-store。单尺寸实际页面核对累计/近5小时的实际及估算、未知调用数正常，无脚本错误；未调用模型、重跑任务或改写正文。

部署记录（2026-10-13 13:46）：作者授权后，核实模型、章节推进、编辑循环、创作对话、记忆、雪花及HTTP/Codex租约全部空闲并重启8081，加载下述B/C结构化输出及失败节点续跑改动。健康UP、5173页面与API代理200；章节推进读取包含failureDetail字段，原失败任务保持FAILED。未调用真实模型、重跑任务或修改正文。

- 自动编辑 B/C 原生结构化输出（2026-10-13）：公共ModelOutputProtocol由调用方显式选择，只有DraftLoopModel的B/C走JSON Schema，A、雪花、大纲、手动质量检查及三章通读仍走原纯文本协议。旧/新Codex传outputSchema；DeepSeek Responses传text.format.json_schema（不附未文档化strict），直接ChatGPT HTTP传同字段及strict=true。预算包含Schema，任务快照记录结构名和哈希；返回拒绝重复键、多余文本、缺失字段、错误类型/枚举/数量，再校验原文证据及C编号覆盖，不静默降级或自动付费修复。章节推进失败返回具体章/阶段/安全原因；显式重试在来源与草稿行版本不变时复用失败子任务，保留B报告、C阶段、轮数和父任务额度。86项后端定向测试、12项前端测试、类型及相关ESLint通过，无依赖/迁移，需重启后端加载。未调用真实供应商或重跑旧任务；详见 ../../docs/features/NOVEL_AGENT_DRAFT_EDITORIAL_LOOP.md。

- 已完成正文一键导出（2026-10-13）：新增 `GET /api/v1/projects/{projectId}/canon-commits/export`，写作工作台顶部下载Markdown，含书名、卷名、章号、正文标题和完整正文。只读取active发布提交绑定的AUTHOR_ACCEPTED稿，且对应canon_memory_job为SUCCEEDED；草稿、新版未发布稿、失效提交、未整理/待确认/失败/跳过记忆均排除，不要求另一次手动质量检查。REPEATABLE_READ只读快照，项目归属先校验，来源大纲按正文writing_basis查卷名并缓存，当前人物姓名统一渲染；无合格章明确报错，不下空书，不写正文/正史或调用模型。6项后端测试（含真实PostgreSQL临时表筛选）、3项前端测试和类型检查通过，无迁移，需重启加载。
  已启用：核实后端原已停止且全部任务/租约空闲后，12:32启动新版8081与前端5173。健康UP、导出及代理200；单尺寸实际浏览器下载成功，真实项目导出第1至3章共8191字符、1个卷标题，页面无脚本错误。相关ESLint通过，未重跑生成、调用模型或改写正文。

- 自动 B 多段证据修复（2026-10-12）：DraftCheck 每项 evidence 改为1至6段独立 quote，实际纯文本协议生成 `@issues/*/evidence/*/quote`，请求明确禁止将不相邻引文、标签或解释拼接。网关保存回调逐段核对当前正文，任一不匹配使整个报告失败，错误定位问题编号和证据索引，归类 OUTPUT_VALIDATION，不进入 C。C 接收同一证据列表，前端分别展示；历史单段保存格式仍可读取。手动质量报告、纯文本模型传输、数据库和小说正文不变，不拆分恢复旧失败响应。34项后端与8项前端定向测试、类型检查和相关ESLint通过；需重启加载。详见 ../../docs/features/NOVEL_AGENT_DRAFT_EDITORIAL_LOOP.md。
  已启用：23:00核实模型、范围、单章循环、创作对话、记忆、雪花及HTTP/Codex租约全部空闲后重启，23:01健康UP，5173页面、API代理及单章循环读取200。未调用真实模型、重跑旧任务或改写正文。

- V062 章节推进（2026-10-12）：范围任务逐章同步复用DraftLoopService A/B/C，删除重复规划器与有限返工执行路径；父子绑定、attempt、取消、额度及来源门禁保护保存。AFTER_COMMIT通知及30秒对账原子认领下一章，回滚/重复通知不重复执行。独立autoPublish默认false、autoMemory默认true、检查1至10轮默认3；自动发布仅B_CLEAR或C全部REJECT，复用CanonPublicationService，不发布DEFER/未复检/异常稿。关闭记忆保存SKIPPED且可手动启动；开启时等有效发布的记忆队列处理结束再冻结下一章。迁移不自动授权旧任务、不重跑生成。详见 ../../docs/features/NOVEL_AGENT_CHAPTER_ADVANCE.md。
  验证与启用：40项后端定向单元、2条隔离数据库发布链路、13项前端定向测试、类型检查、相关ESLint及单尺寸浏览器核验通过；模型/向量/图谱用替身，不改已有小说。21:24确认模型/范围/循环/对话/记忆/雪花及HTTP/Codex租约全空闲后重启，21:25 V062加载、健康UP、5173与章节推进读取200。未重跑旧任务或替已有项目开启自动发布。

- 三章报告协议修复（2026-10-12）：旧 Schema 的 evidence/dimension 使用本地 $ref，但纯文本转换器未展开，错误生成单个 evidence 文字标题，DTO却要求证据数组。PlainTextOutputProtocol统一展开本地引用（拒绝外部/循环/失效引用）并检查数组上限，指令与解码共用展开后的协议；八维各一条明确写入请求。FirstThreeChaptersModelAdapter在网关记录回调内校验DTO和正文证据，失败不再记模型任务成功；只显示安全字段路径，分类OUTPUT_VALIDATION。不改纯文本传输、不恢复旧报告、不调用真实模型；需重启加载。
  验证与启用：38项定向测试通过，覆盖实际Schema→标题→DTO→原文证据、重复维度拦截及FAILED/响应/用量记录。20:30核实全部任务和租约空闲后重启，健康UP、5173正常，三章预算60614 Token且fits=true，正文哈希未变化；未发真实模型轮次或保存报告。

- 前三章输入减重（2026-10-12）：FirstThreeChaptersPersistence 复用 WritingMaterialSelection 的开篇投影，不发送 developmentNotes 九步原始底稿及第四章以后的详细大纲，保留完整三章、开篇计划、全书目标/承诺、人物设定、硬约束、档案和风格。来源指纹仍绑定完整规划，不降低预算阈值或截断正文；模型输入明确未覆盖范围和未来规划边界。32 项定向测试通过，不发起真实检查、不改正文或数据库规划；需后端重启加载。
  已启用：20:13 核实全部任务和租约空闲后重启，健康与5173代理正常；实际三章来源的保守预算从149638降到60417 Token，fits=true，三个正文哈希均未变化。仅核对预算，未调用模型或宣称文学检查通过。

- 本机小说 Fast 已启用（2026-10-12）：独立 Codex runtime/home 配置 service_tier=fast 与 features.fast_mode=true；19:59 全部任务及租约空闲后重启，健康和前端代理通过。原生新建/复用线程均返回 priority，模型仍为 gpt-6.1-sol/xhigh，验证零生成轮次。配置在 Git 忽略的 tmp 下，清空后须重设；不影响 DeepSeek/直接 HTTP，不是项目级数据库开关，不承诺供应商实际提速。详见 ../../docs/design/NOVEL_AGENT_MODEL_EXECUTION.md。

- 自动编辑 C 纯文本协议修复（2026-10-12）：DraftLoopModel 将可空正文定义统一为共享标题协议支持的 object/null，避免 anyOf 被当作单个字符串而只生成 @content；REVISED 明确要求 title/body/summary/continuityNotes，NO_CHANGE/NEEDS_CONTEXT 使用 null。DTO 映射失败显示字段路径及安全的领域原因，不泄露 Jackson 消息中的正文，归类 OUTPUT_VALIDATION。32项定向测试通过，覆盖实际 C Schema→纯文本标题→DTO→编号覆盖及原有循环/保存门禁；无迁移、不改现有草稿、不恢复失败响应或自动重跑。需重启后端加载。

- 发布后记忆整理默认接受（2026-10-12）：任务/状态/写作标签将章节审稿统一改名，不改内部CHAPTER_REVIEW标识。PublishedMemoryPolicy不再使用0.95置信度、中文关键词和主谓宾/摘要逐字包含门槛；六类有效条目默认ACCEPTED。按既有实体解析顺序检查源/当前目录，具名新实体可创建，代词/多重匹配/无效ID/类型或身份变化仍PENDING。共享代词判断加入第一/第二人称。原文证据、锁、有效发布检查及物化门禁保留，不新增模型请求/迁移或回填旧任务。页面已接受项只读、歧义项预选接受并一次保存，初始预选不触发脏稿提醒。10项后端与21项前端定向测试、类型检查通过，后台需重启加载；详见 ../../docs/features/NOVEL_AGENT_PUBLISHED_MEMORY.md。
  已启用：18:39按作者授权核实模型/自动写作/编辑循环/创作对话/记忆队列及HTTP/Codex租约全部空闲后重启8081，18:40健康UP、5173与API代理200。未重跑任务、回填历史记忆或改写正文。

- 正文草稿版本对比（2026-10-12）：前端章节写作新增history视图，复用现有manuscripts摘要及按ID读取接口；支持任意两版、实际基准、左右/合并、字词高亮、差异导航、折叠及路由恢复。读取后核对项目/章节/ID，不写正文或正史、不调用模型、不新增接口/表，无需后端重启。普通保存仍更新当前草稿，不承诺每次保存产生不可变历史。详见 ../../docs/features/NOVEL_AGENT_MANUSCRIPT_COMPARE.md。

- 正文局部编辑保存误报修复（2026-10-12）：局部编辑原来将WritingContext.Context.toString中的OutlineVersion/StoryBibleVersion对象身份混入模型资料和指纹，跨事务重新加载相同实体也会409。改为稳定的卷章文字、来源ID/行版本、相邻章与创作边界；仍检查源稿行版本、写作依据、正史、人物、风格、规划及姓名转换，只创建新草稿。新增真实实体独立读取的回归测试，旧代码可复现失败；修复陈旧测试中的空arc。前端409显示服务端具体原因，成功重新读取后即使正文未变也能解锁，读取失败不解锁。无迁移或原文修改，需重启生效。
  已验证启用：19项后端、10项前端定向测试、类型检查及相关ESLint通过；17:18检查无运行任务/有效租约（含创作对话）后重启8081，健康UP、5173与正文读取200。真实项目原第1版AUTHOR_ACCEPTED保留，不重跑失败请求、调用真实模型或改写已发布正文。

- V061 项目创作对话（2026-10-12）：新增chat模块、PROJECT_CHAT独立会话和配对提示词，活动目录19工作流20模板。应用层纯文本READ/SEARCH/PROPOSE/REPLY循环复用模型网关与记忆检索；完整读取、项目授权与来源版本守卫后只保存修改候选，作者确认才应用。人物多字段与大纲多章节同组原子应用/撤销；雪花单步骤新版本清除依赖后缀，第7步投影只读。正文/正史/确认资料只读，不自动发布。新增project_chat_turn和project_chat_change，页面持久状态、停止、引用、前后差异、确认/拒绝/撤销及section=chat。详见 ../../docs/features/NOVEL_AGENT_PROJECT_CHAT.md。
  验证与启用：本轮19项后端定向测试通过，包含3项隔离数据库链路；前端8项与单浏览器流程、类型检查及相关ESLint通过。16:59按既有授权核实无运行任务和有效租约后重启8081，V061已加载、健康UP、5173与项目对话只读接口200。未调用真实模型、重跑生成或改写已有正文。

- 质量检查误判正文依据过期修复（2026-10-12）：正文生成的Context包含CreativeBoundaryGuide，QualityReviewStore原来却把同一上下文的preparationContext置空，导致未改动项目也无法通过ManuscriptBasis比较。检查入口改为复用WritingContextService.prepared，使用与正文一致的项目边界，不删除版本门禁、不回填或覆盖正文依据。过期提示改为当前“规划资料/大纲/创作策略/创作边界”，不再提已退休圣经/创作准备。32项定向测试通过，覆盖生成后直接检查、作者确认/正史发布后检查、真实策略/边界/规划版本变动拦截及质量/正文/自动循环；未跑全量或真实模型。无迁移或项目数据修改，需重启后端生效。
  真实项目只读核验：9f843969-00bf-46d7-a181-247d1bc387a4第1章第1版AUTHOR_ACCEPTED，正文保存依据与修复后路径完全相等，旧路径不等；当前大纲与规划资料仍为对应发布版本。此前因CHAPTER_REVIEW运行未部署，结束后按作者授权无运行任务再重启，不中断或重跑任务。
  已启用：2026-10-12 16:59重启后健康UP，正文仍为原第1版AUTHOR_ACCEPTED，未保存新正文或检查报告；真实模型检查留给作者手动触发。

- 作品书名输入补齐（2026-10-11）：ModelPromptAssembler 在公共请求层读取当前用户项目的已保存书名，作为明确标注的故事数据直接放入实际请求；雪花各步、人物、大纲、正文及检查等入口统一生效，两条Codex资料去重仍保留短书名内联。BOOK_TITLE本身不传旧书名，automaticTitle.pending为true时不把占位名称当作品名；每轮重新读取，不依赖历史会话记忆。书名仅辅助定位与读者期待，不覆盖事实/授权/风格，不默认成为章节标题。纯文本输出、数据库提示词配置与旧规划不变，无迁移或额外模型调用；需后端重启加载。
  验证与启用：19项定向测试通过，覆盖书名读取、特殊字符、两条Codex去重、入口及Spring注入；未跑全量或真实模型。忙碌检查发现运行任务后先不重启，作者随后明确要求直接重启；23:00重启8081，健康UP、5173和接口代理200。未自动重跑生成，不修改旧底稿或已确认正文。

- Codex 完整资料工具与无效底稿恢复（2026-10-11）：两条 Codex 入口共用 `CodexMaterialReadTool` 的 `novel_read_material(material_id, offset)` 分页协议；旧入口仅在确认线程具有该工具时使用文件引用，限定本轮授权标识、项目目录、内容指纹及只读范围。新线程注册动态工具；恢复线程核对原生会话元数据中的持久工具定义，不向不支持的 resume 协议追加字段。没有工具的既有线程直接发送完整资料，保留原线程，不静默换账号或会话。显式资料读取失败返回 INPUT_UNAVAILABLE，HTTP 502，保留原始响应且不记为有效步骤。只读 invalidSteps 标记旧失败说明及依赖它的后续步骤；作者点击继续后以 CAS 保留有效前缀、清除无效后缀并重做，不自动修复或重跑，不改已确认规划。纯文本输出不变，无新增迁移或文学 Agent；向量/Neo4j 召回仍走已有服务端流程，本轮未新增自主检索工具。86项后端定向测试（含3项隔离数据库链路）、18项前端测试、类型检查和相关ESLint通过；实际安装 Codex 验证工具持久化及进程重启后同线程恢复，模型生成轮次为0。详见 ../../docs/design/NOVEL_AGENT_MODEL_EXECUTION.md。
  启用：22:40按作者授权检查无当前运行任务及有效租约后重启8081，健康UP、5173和API代理200；3个现有项目保留，未自动生成、重跑、清空或更换会话与模型入口。

- 原文证据排版匹配（2026-10-11）：先严格逐字定位；失败时仅支持整行成对斜体/加粗标记省略与CRLF/LF差异，将唯一连续匹配映射回原始字符范围，保存真实原文及章内出现序号。代码围栏、未配对标记、改字/漏句/拼接、范围外证据和多处排版匹配仍拒绝；普通解析项与worldAssessment共用同一定位逻辑。动态引文复制规则覆盖自定义模板，要求短单句、保留内部Markdown、不同依据分列。纯文本协议、作者确认及失败报告门禁不变，不增加模型请求、不回填旧失败任务。后端33项定向测试通过；本次失败响应30项只读验证通过，仅items[15].evidence[2]恢复原始标记，未保存报告或调用模型。
  启用：21:16检查两个项目无当前运行任务及有效租约后重启8081，健康UP、5173代理200。任务响应界面对OUTPUT_VALIDATION单独标“输出校验失败”，不再误标未完成响应；前端6项定向测试与相关ESLint通过，失败记录保持FAILED、原始响应保留。

- V060 直接确认雪花（2026-10-11）：导入生成/继续以prepare-snowflake、resume-snowflake返回SnowflakePlan，成功只标PLAN_READY；删除方向Controller/Service/Graph/供应商生成器/Parser/Schema/默认模板，活动目录18工作流19模板。POST snowflake-plans/{planId}/actions/confirm核对最新完整运行、冻结来源、边界与策略，项目行锁加source_snowflake_id唯一索引防止重复快照；重复确认当前版本不覆盖作者档案。Store.create也在短事务内锁项目，不跨模型请求持锁。大纲直接读取确切雪花和导入来源，不依赖方向表。资料读取/确认响应均带sourceSnowflakeId。前端移除方向菜单，导入结果显示待确认/已确认、确认后进入大纲；创作意图编辑移到项目设置，风格与A/B/C不变。纯文本返回及正史门禁不变，本轮不清空项目、不覆盖全局提示词或登录。详见 ../../docs/features/NOVEL_AGENT_CONFIRMED_SNOWFLAKE.md。
  验证：后端29项、隔离数据库接口链2项、前端41项与浏览器4项通过，类型检查及相关ESLint通过，未跑全量或真实模型。20:12核实无当前运行任务/租约后重启8081并加载V060，健康UP、5173及代理200、活动提示词19份且无方向模板；本轮未删除项目或重跑任务。

- V059 已确认雪花规划（2026-10-11）：移除独立圣经页面、HTTP控制器及活动提示词入口；第7步在原人物请求中整理前置设计并保存prepared_materials，仍为纯文本保存标题协议、不新增模型调用。确认方向核对最新完整运行、必选步骤、边界/策略及导入原文/解析快照，事务内保存确切雪花/方向/候选绑定的内部资料快照并同步人物、规划关系和台账。GET planning-snapshot为只读来源，内部暂复用StoryBible类型和表，不代表保留独立创作阶段。大纲绑定确切来源，正文继续按章检索；用户授权显式清空旧项目，维护工具不在迁移/启动时自动执行，保留全局配置和账号凭据。旧Kafka消息对已删项目跳过。旧服务类尚未全量删除，其独立HTTP入口已移除。详见 ../../docs/features/NOVEL_AGENT_CONFIRMED_SNOWFLAKE.md。

- V058 章节相关资料召回（2026-10-11）：新增可重建 semantic_document_chunk，600字符上限、优先换行/句尾切分，保留原文及UTF-16字符范围；正史投影在短事务内同步分块，Embedding后台每批8块，不在提交/写作时批量嵌入。索引已发布大纲关联的当前圣经 developmentNotes，未确认雪花运行不入池；当前版本与索引全文不一致、正在重建、撤回提交、未来章节、其他项目及不匹配Embedding模型均不参加对应召回。SEARCH_STORY_MEMORY组合精确关键词和向量排名，嵌入失败仍可关键词检索，不把融合排名当可信度或相似度百分比；以片段ID去重，保留同章多个命中。正文/试写/审稿及A/B/C输入不默认携带完整developmentNotes或整卷章节列表，硬约束/规则直接保留，人物名单明确且全部解析成功时才缩减蓝图；不明确仍保守回退。A/B/C复用同一冻结记忆，原文片段预算不足则省略并记录。记忆页同步显示规划/正文来源。无新增Agent、模型生成请求或输出协议变化；本轮尚未撤掉独立圣经工作流，后续改确认源时需调整版本关联与发布门禁。详见 ../../docs/features/NOVEL_AGENT_CHAPTER_RETRIEVAL.md。

- 模型执行器拆分（2026-10-10）：StructuredModelGateway 只读取一次入口并路由，ModelPromptAssembler 共用阶段/系统/事实边界拼接；AbstractModelExecutor 统一冻结模型参数、实际发送预算与记录、请求、纯文本解析、业务保存、成功确认和资源释放。LegacyCodexExecutor、ProjectCodexExecutor、DeepSeekExecutor 三个主要实现，另保留 ChatGptHttpExecutor，均构造器注入、无旧路由回退。新 Codex 项目会话的租约、资料工具与原生压缩不变；DeepSeek 仍完整发送本轮资料。旧 App Server 新增精确长资料去重及项目私有文件补读，不重建旧线程或变更认证；失败不确认发送，进程重启保守重送，未识别区块仍直接发送。删除不再使用的直接会话生成路径。无新迁移、Agent 或提示词版本覆盖；详见 ../../docs/design/NOVEL_AGENT_MODEL_EXECUTION.md。

- V057 Codex 项目会话（2026-10-10）：新增显式 SIWC_CODEX 入口，保留 SIWC 登录与纯文本输出，专用 OAuth App Server 负责持久线程/恢复/原生压缩；规划与正文 A 共用 NOVEL_MAIN，B/C 等检查隔离。阶段指令每轮通过 thread/resume 更新，已挂载线程不提前返回；模板版本不清空主线程。精确内容块存 model_codex_material，重复/过长块引用、只读工具按本轮标识与分页读回，不做模型摘要；model_codex_conversation 跨阶段短租约和每分钟续期，失败轮次解绑、预检失败只释放。入口与账号切换护栏、任务实际引用输入记录、单轮预算不变；仅模拟底层通信与定向/隔离数据库验证，真实模型压缩效果待验证，尚不自动切换现有入口。详见 ../../docs/features/NOVEL_AGENT_CODEX_PROJECT_SESSION.md。

- 规划请求去重（2026-10-10）：导入圣经将作者要求、九步底稿与修订基准分开传递，不再把底稿先塞入要求再重复发送。StoryBibleContent.withoutRepeatedDevelopmentNotes 只创建请求副本；普通圣经修订和导入大纲也去除与单独底稿逐字相同的 developmentNotes，作者修改后的不同文本仍保留。存库与原始版本不变，不裁剪来源、增大容量或新增调用。超容量错误返回输入估算、输出预留、安全余量和窗口数值，纯文本协议不变。

- 导入雪花手动继续（2026-10-10）：新增 imports/{importId}/actions/resume-directions，指定确切 planId；校验作者、最新运行、导入归属、已确认解析版本与原文快照。FAILED/CANCELLED 原子认领，复用同一运行已成功的连续步骤，重试第一个未完成步骤及后续，半截响应不入底稿；完整雪花但方向失败时只重试方向。冻结来源、作者要求、目标字数、可选第9步、创作边界；新运行另存策略快照，模型/提示词使用执行时全局配置。前端导入页匹配 sourceImportId 显示继续按钮，跟踪请求/错误/停止并刷新方向。不新增表/Agent，不自动发布，不自动恢复进程遗留RUNNING。详见 ../../docs/features/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- 世界设定与改编幅度（2026-10-10）：新建/导入/设置提供独立 AUTO 单选；原文解析同次输出带证据 worldAssessment，全部分段校验后写入 novel_project.settings.creativeBoundaries，保存原因与来源指纹。自动改编幅度始终 PRESERVE_CORE，RESTRUCTURE 只由作者选择授权；人工世界选择优先。网关注入公共边界并记录实际 Prompt；雪花九步含第7步冻结到输入，A/B/C 复用冻结上下文，边界进入写作来源指纹。纯文本协议不变，无新表、独立 Agent 或模型请求，不改历史资料，不实现外部现实事实核查。新增 GET/PUT settings/creative-boundaries，行版本冲突409；清除判断只影响下次完整原文解析。详见 ../../docs/features/NOVEL_AGENT_CREATIVE_BOUNDARIES.md。

- 章节规划文字块（2026-10-09）：大纲章节只交付/保存 `number/title/content/status`，`contentNeedsUpdate` 是服务端依据变化元数据。视角、目标、因果、情绪回报、人物、道具、时空、伏笔、字数与场景展开放在同一自由文本，不逐项解析或文学打分。普通、改编、续写、分块大纲统一使用该协议，纯文本返回不变；新版本 `outline/2`、`planning-checkpoint/3`。已有JSONB读取时无损合并旧文字，不批量重写规划或正文。正文、试写、人物召回与冻结B/C均读取完整章节文字块；依据过期则要求先更新规划，不把过期场景拆出继续执行。分块总预算改用阶段数值参考，不解析章节文字中的字数。ChatGPT非SSE错误新增安全状态码、媒体类型、错误码、请求标识和响应种类，读取有上限且受空闲超时/停止控制，不输出原始响应正文，不将JSON完成响应冒充SSE完成。验证与部署见阶段文档。

- V056 ChatGPT OAuth 直连：新增 `modelaccess` 模块，按官方 SIWC 应用注册、PKCE/state/nonce、JWKS 身份签名及账号绑定登录；凭据保存在仓库外仅文件所有者可读的私有目录，跨进程文件锁串行刷新轮换令牌。全局设置可显式选择 App Server 或 SIWC HTTP，无自动账户/供应商/计费回退，默认仍是旧入口。直连每轮向公开 Responses API 发送当前 `instructions`、完整本地历史、`store=false` 和流式请求，不发送输出 Schema；规划共享历史、B/C 独立，只有 completed 且现有解析/保存成功后追加历史。V056 保存用户入口与项目会话租约，不保存凭据。账号管理接口需 `CHATGPT_CONNECTION_ADMIN_KEY`，前端仅内存保存；固定开发用户仍不是正式认证，禁止无保护公网部署。真实账户授权/模型可用性待验证，旧 App Server 的已挂载会话指令更新缺口未在本轮修复。详见 `../../docs/features/NOVEL_AGENT_CHATGPT_DIRECT_ACCESS.md`。
  验证：相关后端24项（含隔离数据库3项）、前端6项、单尺寸浏览器1项及类型/ESLint通过，未跑全量。作者授权后配置仓库忽略的私有管理口令并重启8081，V056已加载，健康UP，5173代理连接查询与本机登录发起/取消可用，无口令403；未授权或请求真实模型，旧模型设置版本5与入口保持。忙碌检查区分本次实例运行任务和跨实例有效直连租约，旧遗留RUNNING不更改、不永久阻塞账号操作。

- V055 配对提示词编辑（2026-10-09）：每个 Agent 的真实系统角色 `sessionSystemPrompt` 与用户阶段指令 `systemPrompt` 在同一提示词页独立编辑、共同保存/恢复/历史版本；原字段保留阶段语义，新字段在配置与历史表可空表示资源默认。网关对 Codex/DeepSeek 统一发送真实系统角色及固定业务边界、用户阶段规范及动态资料，记录实际两份文本，模型输出继续纯文本、供应商不发送 JSON Schema。共享规划会话版本聚合全部规划模板版本，阶段切换复用、保存/恢复后轮转，独立 B/C 会话策略不变。HTTP 日志对新字段只记录字符数。需重启后端加载迁移；不改已生成资料、不自动调用真实模型。详见 `../../docs/features/NOVEL_AGENT_PROMPT_MANAGEMENT.md`。
  部署验证：作者授权后核实 19 个项目无进行中模型/自动任务，已重启后端 8081 并加载 V055；健康 UP、前端 5173 代理接口 200，新配对字段齐全，23 份原阶段文本/规则指纹与配置版本均保持。相关后端与隔离数据库测试、前端 8 项单元/2 项单尺寸交互、类型/ESLint 通过；未跑全量或调用真实模型。

- Codex 流式等待改为空闲超时（2026-10-09）：CODEX_TURN_TIMEOUT_SECONDS默认1200秒改为连续无新生成进展的上限，不再限制总生成时长；当前thread/turn的非空公开输出、非空推理增量和增长的生成用量自动续期，旧轮次、其他会话、空增量、重复用量与状态心跳不能续期。推理仍不进入公开预览；只有turn/completed成功后才解析保存，手动停止、真实空闲超时的精确中断与停止前禁止复用保持有效。协议请求仍用CODEX_CLI_TIMEOUT_SECONDS固定上限，无自动模型重试或失败任务回填。全量681项测试：628通过、53环境门禁跳过、零失败；覆盖持续生成越过旧期限、停滞超时、消息隔离、推理隐私与手动停止。作者明确授权重启后，核实本项目后端身份与17项目无进行中任务，已重新启动8081并确认health=UP、设置接口正常及四份爽文提示词版本1保留；本次修复已加载，未调用真实模型或恢复失败任务。

- 圣经与大纲“爽到底”规则（2026-10-09）：STORY_BIBLE、OUTLINE及素材改编对应两份模板采用作者确认的超级爽文方案，明确主动获胜、短周期回报、收益积累、多样胜利和终局完整兑现；普通大纲动态输入同步移除鼓励无回报压抑或悲剧的通用表述。按实际目标篇幅与指定范围执行，原文续写模板、既往事实、有限修订、JSON Schema、场景自由文本及作者确认门禁不变；默认发布号升级。四份新指令同时通过设置接口保存为当前用户版本1，运行中后续请求立即使用，其他配置和小说数据不改；源码默认发布号与动态输入在下次后端重载时加载。相关37项测试通过、4项数据库门禁跳过，设置接口逐字回读一致。详见阶段文档。

- 原文解析key契约修复（2026-10-08）：实际模型返回点分技术标识，旧Schema未限制字符而Item只允许ASCII字母/数字/_/-。IMPORT_SOURCE_ANALYSIS默认与Schema明确本段唯一1至76字符key，预留最多40段b39_前缀空间。ImportAnalysisOutputParser仅确定性归一化key中的点号为下划线，其他非法格式和归一化重复以items[n].key定位拒绝，不回显正文，不改分类/可信度/证据，不覆盖或丢弃条目；逐字证据、来源、作者确认与任务记录仍严格。无新Agent/迁移/依赖/真实模型调用，不自动修复旧失败任务。详见阶段文档。

- key修复验证：新增4项回归覆盖点分key、故事字段与引文不变、归一化冲突、非法格式、76字符加b39_不溢出及Schema／Prompt一致；全量669项（616通过、53环境门禁跳过、0失败）。真实失败响应31项经新Parser只读验证通过，未保存或改报告状态。确认17项目无进行中模型／自动任务后重载后端。

- 全部爽文提示词审核稿上线（2026-10-08）：24份活动默认模板迁入 `src/main/resources/prompts/*.txt`，AgentPromptDefaults UTF-8加载缓存，设置页与生成入口共享；DraftEditorialPrompts不再重复维护A/B/C指令。保留所有现行JSON Schema、解析、保存/同步、模型预算、请求次数与人工正史门禁，正文字符串不混报告；developmentNotes由服务端保留雪花全文而非新增输出字段。07整理跨章关系/知识/时间线/台账，08独立复核，不重复人设和场景；试写修订启用独立STYLE_PREVIEW_REVISION。公共风格执行指南保留既定行动/压力/回报，12个数据库风格不改写。默认发布号commercial-2026-10-08纳入Codex复用会话版本，数据库自定义系统指令不覆盖、无配置行写入或历史内容修改。无新Agent、依赖、迁移、文学字段校验及付费模型调用。详见 ../../docs/design/NOVEL_AGENT_ALL_PROMPTS.md 及阶段文档。

- 本轮验证：全量665项（612通过、53环境门禁跳过、0失败），另4项隔离数据库提示词集成测试通过，覆盖设置接口默认值、覆盖/恢复、记录器、雪花与圣经持久化及A/B/C循环；隔离schema已清理，不改现有项目或全局配置。确认16项目无进行中模型/自动任务后重载后端8081，24份运行时默认与资源一致，前端5173正常。真实文学效果待生成评测。

- V052 单章自动编辑闭环（2026-10-08）：DraftLoopService/Store/Context/Model 从大纲执行 A（MANUSCRIPT）→B（QUALITY_REVIEW）→C（DRAFT_JUDGE_REVISION）→B，或直接检查当前有效草稿；冻结共同创作依据，B/C 独立新会话，不要求作者本轮输入、选择问题或轮间确认。B 无评分，逐字证据、已有依据与候选补丁分开；C 逐项接受/拒绝/暂缓，仅修复计划内且不改核心事实的问题，更新全文及摘要。默认最多10轮、可选1至10；空问题、无需改、资料不足、无进展、震荡、来源变化、取消、失败均结束，上限后最后稿明确未复检。draft_loop_run 保存来源、轮次、裁决、前后稿关联与实例身份，正文只新建 DRAFT，不接受或写正史；与运行中范围任务互斥。前端正文/质量页提供入口、轮次/阶段/停止、历史、裁决与实际差异，刷新可恢复；同实例重启标 INTERRUPTED 不自动重试。公共任务记录保存 Prompt/响应/用量，自定义 C 保留权限边界；活动目录22工作流/24模板，未覆盖现有自定义 A/B。无新增依赖或真实付费调用，需重启后端加载迁移；详情见 ../../docs/features/NOVEL_AGENT_DRAFT_EDITORIAL_LOOP.md。

- 导入大纲缺创作意图修复与首章目标强化（2026-10-08）：OutlineService 不再仅为取得篇幅而强制独立 CreativeIntent 记录；已保存意图优先，否则沿用指定基准大纲/最新大纲的 wordBudget，不创建猜测的作者意图或额外调用。两者皆无时明确提示到故事方向保存目标字数。OUTLINE 与两种导入反推大纲、PLANNING_CHECKPOINT 的默认系统指令共享第一章吸引力要求，落实即时目标/行动压力、有铺垫的反转、题材回报及后果钩子；STANDARD 也执行第一章目标，FANQIE_GRIPPING 仍另管完整前三章短弧。作者慢热/指定开场、事实边界与有限修改优先，OCCURRED 不重写；不增加结构校验、输出字段或请求。不自动覆盖数据库自定义提示词与旧稿，需重启加载。

- 场景级大纲（2026-10-08）：OUTLINE/IMPORT_REVERSE_OUTLINE/PLANNING_CHECKPOINT 在原有请求中按章输出 sceneOutline 自由文本，合并场景清单与关键场景展开，不新增当前章展开请求、不改模型强度/令牌上限。ChapterPlan 随大纲 JSONB 保存文本及服务端 sceneOutlineNeedsUpdate；章节、幕、全书或来源圣经变更而文本未更新时保留旧稿并提示待核对。正文/试写直接读取，有效文本进入 writing_basis，失效文本不列作必写节拍。前端每章可查看/编辑，已发布版本只读。没有新 Agent/合同审批/文学结构校验/迁移/依赖；不改已有项目和自定义提示词，后端须重启。详见 ../../docs/features/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- 人物设计权限修正（2026-10-08）：CharacterBlueprintGuide 统一区分原创/授权改编的新设定与续写原文提炼，核心人物补具体姓名（保留已有姓名和作者匿名要求），展开经历→应对方式→需求矛盾→选择→关系后果。CHARACTER_DESIGN 不再叠加通用未知留空指令；雪花/普通圣经/导入圣经显式传递模式，圣经取消一两句压缩，developmentNotes 全文与大纲背景因果沿用。补全、准备、有限修订、审阅和正文事实边界不放宽；不增加Agent/调用/依赖/迁移，不改已有项目或作者自定义提示词。相关50项通过，全量613项（561通过、52环境门禁跳过、0失败）。需重启加载新默认规则，真实文学效果仍需实际生成评估。详见 ../../docs/features/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- V051 雪花渐进规划（2026-10-07）：SnowflakePlanningService 将新故事/已确认导入按核心与一段梗概→统一人物设计自由文本→世界构建→三幕情节串行扩展，再整合圣经；有限修订不重跑四步。CORE/WORLD/PLOT 共用 SNOWFLAKE_PLANNING，人物仍 CHARACTER_DESIGN，目录为21工作流/23模板。FreeTextPlanningRequest 用单text传输原始自由文本，仅检查可读非空，不增加弧光、三幕或世界维度结构校验。snowflake_planning_run 保存来源快照、四步全文及 RUNNING/SUCCEEDED/FAILED/CANCELLED，失败保留前置结果但不自动恢复或复用。圣经 developmentNotes 保留完整底稿，大纲读取；作者修订后的明确字段优先于旧底稿。GET /projects/{projectId}/snowflake-plans/latest 限项目作者；圣经/导入页展示阶段状态与全文，底稿可随圣经编辑。公共网关模型配置、预算、任务响应与停止复用，私有文本日志脱敏。前文召回、状态/向量投影、作者确认及正史边界不变，没有新增依赖或真实付费调用，不改已有项目数据；重启后端加载V051。验证：服务端607项（555通过、52环境跳过），另隔离PostgreSQL/Spring三项通过；前端218单元/11交互测试通过，类型、构建、改动文件ESLint通过。详细方法、接口与验证见 ../../docs/features/NOVEL_AGENT_SNOWFLAKE_PLANNING.md。

- V050 流程简化：当前活动目录 20 种模型工作流、22 份提示词模板。合同与合同审阅退出前端、自动创作与正文门禁，旧写入入口返回 410。正文从当前已发布圣经/大纲直接生成，持久化 writing_basis（大纲 ID、完整上下文指纹、本章计划快照）；质量、局部编辑和审稿读取同一依据，作者确认与正史提交仍显式执行。统一 CharacterDesignService 承接新故事人物设计、导入原文确认后的设计、圣经人物补全、创作准备人物世界设计，统一 CHARACTER_DESIGN 记录和可编辑提示词；驱动力三角、五阶段弧光、关系冲突、事实与未来计划边界共用。导入按人物→反推圣经→反推大纲串行，前一步失败不继续。旧数据不作为本轮兼容验收目标，生产清理范围须经作者明确。验证：全量 596 项中 546 通过、50 环境门禁跳过；另隔离 PostgreSQL/完整 Spring 装配 49 项通过，前端 213 单元与 10 浏览器交互通过，类型/构建/改动文件 ESLint 通过。无真实付费模型调用或生产资料修改；现有后端尚未重启。详细主线及可选支路见 ../../docs/design/NOVEL_AGENT_SIMPLIFIED_WORKFLOW.md。下列旧日期条目是能力演进记录，涉及合同的历史描述已被本条替代。

- 公共能力重构（2026-10-07）：13 个业务服务的重复项目归属校验统一调用 ProjectAccessService；保留原服务写事务、锁、来源版本及作者确认门禁，锁后实体可用 requireOwnedProject(NovelProject) 重新检查权限而不额外查询。StructuredModelGateway 拆出包内 CodexSessionManager（会话策略/版本轮转/缺失恢复/完成回写）和 StructuredRequestBudget（最终上下文容量校验），入口及默认 Prompt/Schema/输出上限/解析回调保持不变，不新增 Spring Bean。platform.support.Sha256 统一 12 个类的 13 处指纹算法，文本 UTF-8、原始字节、小写 hex、序列化内容及调用方 null 规则不变，不需要数据回填。ArchitectureTest 新增权限/指纹/网关委托三条字节码约束；新增并发哈希、预算边界、失败 turn 不回写、progress 透传和锁后归属复核测试。完整测试 598 项：默认 548 通过、50 环境门禁跳过；另外 49 项隔离数据库/完整 Spring 装配测试全部通过，合计 597 通过、1 项真实模型评测未启用、无失败。本轮无新依赖、接口变化或迁移，不操作现有项目或调用付费模型；后端尚未重启加载本轮改动。详细当前结构见 ../../docs/design/NOVEL_AGENT_JAVA_ARCHITECTURE.md。

- V049 全局提示词管理：新增 prompt 模块，AgentPromptDefaults 与原生成器共用默认系统指令；AgentPromptCatalog 覆盖 23 个工作流、25 份模板（导入反推圣经/大纲各分改编和续写）。当前用户配置与不可变历史保存在 user_agent_prompt/user_agent_prompt_revision，GET/PUT /settings/prompts、POST /{key}/reset、GET /{key}/history；两个文本各限 40000 字符，更新带 version，过期 409，读取不写入。StructuredModelGateway 在预算/记录/供应商请求前读取并冻结配置，支持系统指令替换和阶段规则；未编辑请求文本不变，动态项目资料、作者要求、Schema/令牌预留/解析/门禁不改。自定义文本追加固定事实/知识/作者优先级/候选输出边界，任务保存实际文本与配置版本，不改历史结果。codex_agent_session.prompt_revision 变化（包括 RESET）时换新会话，同版沿用旧策略；DeepSeek 无状态。前端 /settings/prompts 支持搜索、URL阶段恢复、保存、默认/边界查看、最近50版载入、并发冲突与未保存保护；不是修改风格预设目录或运行第三方 skill。无新增依赖、无真实模型调用；需要重启后端加载，详细范围与验证见 ../../docs/features/NOVEL_AGENT_PROMPT_MANAGEMENT.md。
  验证：全量 584 项（534 通过、50 环境门禁跳过），另外隔离 PostgreSQL/完整 Spring 装配 4 项通过，实际 API 保存后的文本由网关发送到模型替身并记录，RESET 不回填历史或写正史；前端 211 单元/23 相关浏览器测试通过，构建/类型/ESLint/格式检查通过。新提示词字段在 HTTP 日志仅记录字符数与版本。自动重启被环境限制拦截，当前旧后端未停止，现有项目 schema 尚未加载 V049，需手动重启；不将隔离测试迁移当成生产部署成功。

- 作者要求与策略分离：大纲生成不再将系统策略拼入作者 instruction，OutlineService、Graph 和三种生成器分别传递作者原文与 CreativeStrategyPolicy；Codex/DeepSeek 共用 Prompt 工厂，作者要求前置，策略独立标注系统来源。有效上游约束、OCCURRED 和已确认事实仍不可越过；在边界内作者明确开场、回忆框架与节奏优先于通用策略，空要求不授权整体重写，不能从作者原文猜测项目策略。调整冲突在既有 changeSummary 说明，上游变更仍需作者操作；共享策略指南补充相同边界，不新增模型步骤、Schema、接口或迁移，不追溯修改任务 Prompt 和旧稿。全量 562 项：516 通过、46 环境门禁跳过、无失败；覆盖作者开头保留、空要求、策略独立传递及两个供应商入口，均用模型测试替身，未对真实文学质量作保证。后端需重启加载。实际模型工作流仍为 23 种，记忆预算的 AgentStage 六项不是完整清单，详见 ../../docs/design/NOVEL_AGENT_STAGES_AND_PROMPTS.md。

- 业务分层注释：128 个服务、控制器、Repository/JDBC 存储和响应转换文件补充中文职责说明，557 个方法补充操作边界及参数说明；关键处注明项目归属、行版本与生成序号、短事务与模型等待、规划与正史、作者确认与发布、幂等及迟到结果隔离。当前无独立 MyBatis Mapper，映射注释位于实际 JDBC 行转换、JSON 恢复和响应转换方法，不新增空层。新增说明之外仅有行尾格式变化，未修改业务代码、SQL、Prompt、接口或依赖。验证：逐文件移除本轮新增注释后与修改前来源一致（忽略换行风格及文件末尾空行）；后端全量 556 项，510 通过、46 环境门禁跳过、无失败。后续修改行为时应同步维护相邻注释，不能将注释视为替代代码与测试的业务保证。

- 强开篇提示词强化：CreativeStrategyGuide 共享策略边界和各阶段落点；FANQIE_GRIPPING 要求首段当前矛盾、眼前欲望／回应代价、行动选择与类型回报，禁止用无因果接入的远期预告或完整讲题流程充当开场推进。完整大纲／分块规划在现有字段落实三章短弧，合同细化入场与回报节拍，正文压缩无效操作；试写及独立审阅检查片段已展开的压力，质量／合同／三章审阅区分上游缺设计和执行不到位。不添加模型步骤、Schema、评分维度、迁移或自动重写；STANDARD、OCCURRED、视角知识与有限修订边界不变，策略规则不受可选 craft-rules-enabled 开关关闭。导入改编／续写原来未传项目策略，现两次原有请求均传入同次读取的策略指南，大纲补共享落点；续写已发生章仍不可修改。已有大纲／合同／试写不会自动变好，须作者明确调整上游并重新生成；真实阅读吸引力尚未对照评测。
  本轮验证：全量 556 项中 510 通过、46 环境门禁跳过；另行隔离 PostgreSQL 导入测试 1 项通过，验证两次请求策略与仅保存草稿，模型使用测试替身。没有调用真实模型或改真实项目资料。自动重启被执行限制拦截，需重启后端加载新代码。

- V048 新增数据库预设“校园关系：清爽叙事”（campus-relationships/v1），按主体朴素行动、日常人情声口、情感落差观察、少量第一人称自嘲组合具体技法；保存六维字段、八维技法与两组原创正反例，保持事实／知识边界和既定视角，强开篇仍服从项目策略，不为风格强造暗恋或转学等情节。现有数据库列表、应用、试写、推荐和共享检查／润色指南直接复用，不改默认风格或任何项目已选配置，不新增作家身份模式；旧 11 个预设版本不变。

- 通用模型停止：生成状态面板对运行中的请求／模型任务提供停止按钮；POST generation-requests/{requestId}/actions/stop 与 agent-runs/{runId}/actions/stop 先校验项目归属，再中断本进程的准确模型调用。HTTP 通过 X-Generation-Request-Id 关联 Recorder；停止和输出处理入口互斥，已停止的迟到响应不进入解析／产物保存，已经进入保存阶段则返回 409，不承诺撤销完成结果。AgentRun 持久化 CANCELLED、已有响应／可得用量，SSE／前端识别已停止；原文解析和创作准备任务同时标记 CANCELLED。Codex 发送 turn/interrupt，未确认结束前阻止会话复用；DeepSeek 改用可中断 JDK HTTP 等待，不保证供应商停止计费。无依赖／迁移；不是单纯中断浏览器 fetch。运行注册表仅限当前进程，升级前任务、尚未进入模型和保存中请求不能停止；自动创作外层任务仍按原恢复规则处理，详见 ../../docs/features/NOVEL_AGENT_GENERATION_STOP.md。

- 人物档案整合：CharacterBlueprint 增加可空 gender（40）与 ageDescription（100），旧 JSONB 缺失归一为空，保留旧 Java 构造入口；StoryBibleOutputSchema/人物补全/创作准备共用结构，发布及准备确认同步独立档案的性别年龄，仅填空白，不覆盖作者已有值。指南禁止从姓名、外貌、年级猜测确切年龄，无新迁移/依赖。前端人物命名、人物实体事实、规划和正史关系共享一个人物档案入口；非人物实体单独保留。关系读取仍使用原规划/正史来源，不写新事实边；补全只创建待发布圣经 DRAFT。未保存编辑保留原行版本，刷新不悄悄升级乐观锁。后端需重启加载，真实旧项目未调用模型或改资料，详见 ../../docs/features/NOVEL_AGENT_CHARACTER_DOSSIER.md。

- HTTP 请求日志：platform.web 下 Filter + HandlerInterceptor + BodyAdvice 关联 /api/** 的 HTTP_START / PARAMETERS / INPUT / END，包含服务端生成的 X-Request-Id、路径所属 projectId、参数／JSON 摘要、响应状态、耗时；MDC 将同请求线程的模型日志关联，退出恢复原值，异步线程不自动继承。无所属项目用“-”，创建项目成功后 END 补新项目 ID。输入仅读转换器已读取的 JSON，不重复读取原始流；无效 JSON／MVC 前拒绝时只记状态和未读标记。文件仅元数据、二进制仅字节数，SSE 仅连接与完成信息，不缓存或逐条记录流。凭据字段递归脱敏，已知手稿／Prompt／响应文本字段隐藏；摘要最多 4000 字符、20 数组项及深度／节点限制。HTTP_LOG_LEVEL 默认 INFO，可设 OFF，沿用滚动日志；不新增依赖／迁移，不改变导入业务流程。详见 ../../docs/ops/NOVEL_AGENT_HTTP_LOGGING.md。

- 原文解析输出修复：ImportAnalysisOutputParser 在模型边界检查字段与类型，错误保留 items / evidence 索引及安全的领域原因。非线索项的有效 progress 归一 NOT_APPLICABLE，UNKNOWN 分类仍拒绝；唯一逐字引文确定性纠正序号，重复引文／假引文继续拒绝。IMPORT_SOURCE_ANALYSIS 使用公共网关可选处理回调，解析及短事务保存完成后才记成功，失败保留响应、可得 usage 与 OUTPUT_VALIDATION 分类；其他旧调用边界不变。不新增迁移，不回填失败报告或自动付费重试。后端 522 项（477 通过、45 环境门禁跳过），本次未运行真实模型或数据库重放；需重启后端加载。

- V047 原文统一解析：ingest 下 ImportAnalysisStore/Runner 与领域校验分离；按所选完整章节分段保存人物/世界/关系/事件/线索/伏笔，FACT/INFERENCE/UNKNOWN 和逐字证据分开。每次一个模型新会话，完成全部段后作者逐项决定并确认模式；改编支持带要求的重构，续写禁止重构原文，不采用结论不等于改写过去。reverse-plan 必须携带当前已确认 analysisId/version，两个生成请求读报告，草稿事务内重检；不发布或写正史。移除旧 80000 字符截取，超预算拒绝，不伪造完整检查。报告、恢复/取消、来源及幂等策略见 ../../docs/features/NOVEL_AGENT_IMPORT_ANALYSIS.md。
- V046 创作准备：CreationPreparationStore/Runner/ApprovalService/ContextService 分离来源快照、串行模型调用、确认事务和写作上下文。PREPARE 三步设计人物/实体/初始状态、剧情单元/关系/知识/时间线/明确台账、一致性报告；REVIEW 仅检查，作者选择未来章才创建大纲 DRAFT。范围按章节和剧情单元，不固定 15 万字；模型事务外，保存复核来源/行版本，显式失败恢复与取消迟到隔离。确认后档案只补空白、规划实体不写事实，当前准备指针按版本替换；合同/正文/审稿/质量/试写读取适用规划。单元节点只读，复核后正文事实与计划关联需另外确认登记台账，不自动审批或发布。隔离 PostgreSQL 34 项通过，无付费模型或生产资料变更；部署及限制见 ../../docs/features/NOVEL_AGENT_CREATION_PREPARATION.md。
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
- 本轮范围和验证见 ../../docs/roadmap/NOVEL_AGENT_REFERENCE_INTEGRATION_DELIVERY.md。按用户最新要求串行开发；正文每次任务生成一份草稿，优先完善单篇生成、检查、受控修订和复检，不增加批量正文供挑选的流程。novel.writing.craft-rules-enabled=false 只关闭新增写作技法块，不回退全部策略/大纲/既有风格规则。真实文学评测、已有正史的增量分块调整及 20 章连续创作回归仍未完成。
- 写作评测离线基础：测试资源 `evaluation/writing-corpus-v1.json` 固定四类故事资料、26 个场景正反例及三组完整微型三章；`WritingEvaluationBaselineTest` 从真实 WritingGenerationGateway / WritingModelRouter 截取 44 份请求，外部网关和输出解析器使用测试替身，不启动数据库或模型。显式传 `novel.evaluation.output` 可导出不可覆盖的输入 / Prompt / Schema 哈希和未运行结果模板，说明见根目录 `../../docs/README.md`。仅验证结构、来源、标签隔离与可重复性；初始标签待人工复核，真实模型、常规长度三章及读者效果未评测。未改变生产 Prompt、接口、依赖或调用数量。
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
- 相关正史事实读取 PostgreSQL，不依赖 Neo4j；向量检索不可用时保留关键词召回。
- Codex App Server 长驻进程；大纲和正文每次生成新 thread，其他阶段按项目、工作流复用 thread。
- DeepSeek Responses JSON Schema 结构化输出。
- Spring AI Alibaba Graph 编排八个标准创作模型阶段（含独立质量检查）的输入校验、模型生成和输出校验；样本风格分析独立调用。
- 已确认作品导入可通过两个专用模型阶段反推故事圣经和大纲草稿，并区分已发生章节与未来规划。
- 写作生成已拆分为 Prompt、Schema、解析、本地模板、模型路由和稳定网关门面。
- Java 用例职责拆分：WritingService 仅委托 ChapterContractService、ManuscriptService、ChapterReviewService；WritingContextService 统一写作上下文，QualityReviewService 直接使用 ManuscriptService。运行查询、投影状态和记忆预览 Controller 不再持有 Repository/JDBC；查询服务通过 ProjectAccessService 验证权限，SQL 留在独立查询仓库。详细职责见 `../../docs/design/NOVEL_AGENT_JAVA_ARCHITECTURE.md`。
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
├── modelaccess   模型调用端口、供应商执行器、协议、预算、目录、认证与会话
├── planning      雪花规划、确认资料快照、大纲及阶段生成适配
├── writing       正文、质量检查、自动编辑循环；合同仅历史兼容
├── memory        关键词与 pgvector 召回、正史事实读取、Embedding、Token 预算
├── canon         正史提交、Outbox、Kafka 和投影
└── platform      Web 配置、统一异常处理等横切能力
```

每个业务模块优先采用：

```text
api / api.dto   Controller、请求和响应 DTO
application     用例服务、工作流和端口
domain          实体、值对象、状态机和领域规则
infrastructure  Repository、外部模型、数据库和消息适配
```

不要让 Controller 直接操作 Repository。领域状态转换放在领域对象中；跨聚合用例和事务边界放在 application 层；供应商 SDK、SQL、Cypher 和 HTTP 调用放在 infrastructure 层。

### Controller / Service 格式与注释

- `api/*Controller.java` 与 `application/*Service.java` 统一四空格缩进，使用 `scripts/format-server.ps1` 格式化，`scripts/format-server.ps1 -Check` 只检查。脚本固定 Google Java Format 1.24.0 的 AOSP 风格，首次运行从 Maven Central 下载至系统临时缓存；不添加服务运行时依赖，不重排 import、改写文档或重排字符串。Java 编辑器约定见本目录 `.editorconfig`。
- 类注释说明职责和协作者边界；公开业务方法注明实际读写、副作用、异步执行、版本条件和作者确认要求。参数区分项目 ID、章节号、运行 ID、行版本与正史水位；不以“执行本模块操作”等通用句式代替业务说明。
- 普通构造器、简单访问器及框架实现无需重复名称的注释。旧兼容入口应明确退役或历史读取语义，模型生成不可写成自动发布，后台记忆整理不可写成正文发布门禁。
- 格式与注释整理不应改变请求映射、事务注解、模型提示词、SQL 或业务分支；必要时用语法树或编译验证改动范围。

写作内部：`WritingService` 为无事务兼容门面，仅有三个用例服务依赖。查询/作者修改的 `@Transactional` 声明位于专用服务的公开方法；模型生成入口不声明长事务。审稿打回仍在模型调用结束后由 `writingTransactionTemplate` 原子保存候选与审稿状态。质量修订使用 ManuscriptService 准备候选，由既有 QualityReviewStore 复核并保存，禁止回调门面形成循环。

人物档案：`CharacterProfileService` 通过 application 层 `CharacterProfileStore` 端口访问存储，不直接依赖 JDBC 或基础设施。`JdbcCharacterProfileStore` 负责项目/有效人物范围 SQL、命名与别名投影、档案映射、幂等补空及条件版本更新；端口不依赖 Spring。权限、有效人物检查顺序、版本冲突解释、人物引用精确解析、资料不确定时的全量回退与知识边界留在应用服务。list 补空与 update 仍由原有写事务包裹，promptContext 保留只读事务；适配器不另开事务，也不反向依赖服务。

类型化正史：`TypedCanonMaterializer` 保存通用 story_fact 并按规范化类型选择 application 端口 `CanonFactProjector`。infrastructure 下 Event/State/Relationship/Knowledge/ForeshadowFactProjector 分别负责各自表的写入、主体需求及历史退役；每个策略显式声明旧/新类型别名，重复注册启动即失败。未知类型只保存通用事实，未知状态字段不猜测，关系自环仍省略。退役按策略实例调用一次，不因别名重复执行。策略不另开事务或调用协调器，异常向上传播，由现有正史/记忆用例回滚通用事实及全部投影；批次成功后才同步规划伏笔。新增投影策略无需修改中央类型 switch，不引入新的通用 CRUD 或装饰器层。

正史存储分层：通用事实经 `CanonFactStore` / `JdbcCanonFactStore` 写入与退役，`TypedCanonMaterializer` 不再直接依赖 JDBC。`EntityResolutionService` 仅控制明确 ID、标准名、确认别名的匹配顺序、歧义/代词拒绝及出现记录的业务含义；候选查询、实体幂等创建和出现记录 upsert 由 `EntityResolutionStore` / `JdbcEntityResolutionStore` 承担。两个应用服务不依赖基础设施类；存储端口不依赖 Spring，不新增事务。已删除无生产调用的 hasCommittedChapter、旧人物补全 fillMissingFrom 及私有 fill；有效重复提交门禁、蓝图历史反序列化与性别年龄约束保留。

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

正史替换在同一事务中将旧提交置为失效、关闭旧提交的类型化事实有效区间、物化新事实并写 Outbox。实体身份可跨提交复用，不随旧事实关闭。写作工具按有效提交过滤语义文档，相关事实直接按 PostgreSQL 正史有效区间读取；消费者收到新事件后清理旧版语义文档和图谱事实。后续章节已有正史时仍需单独的依赖分析与连锁修订流程。

### 6.3 长期记忆召回

入口：

```text
NovelMemoryService.recall(stage, projectId, chapterNumber, canonVersion, query, budget)
```

输入 query 由章节标题、POV、目标、核心事件和作者本次要求组合。召回包括：

- 不高于目标正史版本的语义文档。
- 与查询中的实体或事实相关的 PostgreSQL 权威正史事实。
- 相关事实无命中时的少量近期事实回退。
- 向量查询不可用时保留关键词检索；PostgreSQL 权威事实查询失败直接报错。
- `MemoryBudgetAllocator` 按阶段与章节远近分配前章摘要、正文、精简合同和图谱事实额度，并确保合成后的 Prompt 记忆不超过有效预算。正文阶段 n-1 最近章节总额度不超过有效预算 65% 和 5400 Token，n-2 不超过 18% 和 1500 Token；有事实候选时预留最多 800 Token，剩余留给语义检索及其他事实。合同与审阅阶段沿用较短前文。
- 文本预算集中在包内 `MemoryTextBudget`：沿用 ASCII 连续段每四字符约一 Token、非 ASCII 每码点一 Token 的估算，前缀按码点线性裁剪并为省略号预留额度，正文尾部按完整段落反向累计，不反复创建和扫描增长的子串。`MemoryBudgetAllocator.estimateTokens` 保留公共兼容入口；来源指纹用 StringBuilder 按原顺序拼接，哈希格式不变。单 Token 裁剪只保留省略号，避免局部超预算；补充字符不截断 UTF-16 代理对。最终完整 Prompt 仍须重新估算，定位证据片段仍整段保留或省略。

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

`modelaccess.domain.ModelProvider` 当前包含：

- `LOCAL_CODEX`
- `DEEPSEEK`
- `LOCAL_TEMPLATE`

API 层只传递供应商选择，领域服务通过对应 Registry 或 Gateway 路由。不要在 Controller 中判断供应商。

### 7.2 Codex

Codex 使用后端主机上的 `codex app-server`，不是前端浏览器或用户电脑上的临时进程。

核心类：

- `CodexAppServerClient`：进程、协议、thread、turn 和结构化输出。
- 默认模型 `gpt-6.1-sol`；`CODEX_EFFORT` 默认 `xhigh`，由 `CodexAppServerClient` 在每次 `turn/start` 显式传递 `effort`，不是依赖 Codex 的默认推理强度。更高强度可能增加延迟和 Token 消耗。
- Codex 协议请求默认最多等待600秒（CODEX_CLI_TIMEOUT_SECONDS）；生成按连续无新进展1200秒（CODEX_TURN_TIMEOUT_SECONDS）判断空闲超时，持续输出或推理时自动续期，不设总生成时长上限。运行后端的系统用户必须能读写自己的Codex运行状态目录，否则App Server无法初始化。
- `CodexAgentSession`：项目与工作流对应的持久化会话。
- `CodexAgentSessionRepository`：恢复 thread。
- `modelaccess.application.StructuredModelGateway`：业务调用端口。RoutedStructuredModelGateway 选择执行器，ModelPromptAssembler 按注入的阶段规则组装提示词；AbstractModelExecutor 统一预算、记录、解析、保存及成功确认，会话细节由各执行器的协作者处理。
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
| V061 | 项目创作对话、来源引用、修改候选与确认/撤销记录 |
| V062 | 章节推进A/B/C父子任务、等待发布、检查轮次与自动发布/记忆独立开关 |
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
| V056 | 用户 ChatGPT 入口选择与 HTTP 会话历史/租约；OAuth 凭据不入库 |

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

后续失败重放和全量重建设计见 `../../docs/design/NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md`。

## 10. API 现状

当前 Controller：

| Controller | 主要能力 |
| --- | --- |
| `ProjectChatController` | 项目创作对话、停止、资料分页预览、候选确认/拒绝/撤销 |
| `ProjectController` | 项目创建、列表、详情、创作意图 |
| `GlobalModelSettingsController` | 当前用户全局模型设置、乐观版本更新、ChatGPT 运行时模型与强度目录 |
| `ChatGptConnectionController` | 管理口令保护的 ChatGPT OAuth 登录/回调/取消/断开与显式入口切换 |
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
| `WritingController` | 正文、正文审稿的生成与确认及审稿打回重写；历史合同读取与退役写入口 |
| `CanonCommitController` | 正史提交、状态查询与同章正史替换 |
| `ProjectionStatusController` | Kafka、pgvector、Neo4j 投影状态 |
| `MemoryController` | 长期记忆召回预览 |
| `TypedCanonController` | 实体、状态、别名、提及、事件、伏笔、人物关系和知识边界查询 |

接口设计原则：

- 自动任务接口为 `/api/v1/projects/{projectId}/automation-runs`；创建必须携带 UUID `Idempotency-Key`，继续和取消分别使用 `/{id}/actions/resume`、`/{id}/actions/cancel`。
- `AutomationRunStore` 在短事务中锁定任务；`AutomationService` 在独立线程调用现有写作用例，禁止在模型调用期间持有任务事务。每次执行检查 attempt，旧执行者不能更新恢复后的任务。
- 自动任务逐章复用A/B/C，默认由作者发布。仅作者在本次任务明确开启autoPublish时，满足门禁的稿件通过现有发布服务提交正史；单章循环仍不发布。大纲变更须取消旧任务后重建。
- 超过20分钟未更新的运行可显式恢复；取消即时持久化并中断当前子循环，迟到结果不保存。已发生但未提交正史的导入章节等待人工登记。发布通知在提交后执行，等待autoMemory队列结束再冻结下一章，关闭时不排队。

- 路径统一以 `/api/v1` 开头。
- 项目资源必须验证当前用户所有权。
- 编辑使用 JPA `rowVersion` 或请求中的期望版本防止静默覆盖。
- 生成新候选应创建新版本；发布状态的内容不可原地编辑。
- 非法状态转换抛出明确业务错误，由 `ApiExceptionHandler` 转为统一响应。
- 合同及合同审阅的五条写入路由统一检查项目归属后返回 410，不调用模型、不保存产物。Controller 只区分 POST/PUT，门面与合同服务各用一个拒绝入口；不再绑定旧请求体或解析 If-Match，删除两个旧更新请求 DTO。历史 GET、响应 DTO 和 ETag 保留；RetiredContractControllerTest 覆盖权限、空/非法旧参数和历史读取。
- 新增高风险写接口时设计幂等键，不能只依赖按钮防重复点击。

## 11. 配置

主配置：`src/main/resources/application.yml`。

敏感值只通过环境变量或 `.env.local` 提供。需要关注的配置组：

```text
spring.datasource.*
spring.neo4j.*
spring.kafka.*
app.ai.codex.*
app.ai.chatgpt.*
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

- 内容更新包装统一使用 `platform.api.dto.ContentUpdateRequest<T>`：大纲、正文、审稿保留各自的具体 content 类型和 `{content: ...}` JSON；是否触发 Bean Validation 仍由接口的 `@Valid` 决定，大纲必填、审稿批准可省略 body 的行为不变。规划批次/片段共用 `planning.api.dto.VersionRequest`，保留必填非负行版本校验。不把状态、来源字段或授权范围不同的响应/修订请求强行合并。
- API DTO 不直接承担领域状态机。
- 模型输出 DTO 必须经过转换和领域校验后才能持久化。
- JSONB 适合不可变版本快照和供应商输出，不适合永久替代所有可查询领域表。
- PostgreSQL ID 是跨存储稳定标识，禁止把 Neo4j 内部 ID 写回业务表。

## 13. 测试与验证

日常采用低负载验证，不再每次修改都运行全部测试。默认后端运行 10 个关键测试类（包含 ArchitectureTest），单 JVM、关闭并行、测试堆上限 512 MB、可用处理器数 2；不启动 Spring 上下文、数据库或模拟长时间超时。

```powershell
.\mvnw.cmd test
# 指定本次改动涉及的测试，不受默认清单限制
.\mvnw.cmd -Dtest=SomeServiceTest test
# 手动完整验证，不作为日常默认命令
.\mvnw.cmd -Pfull-tests test
```

前端 `npm run test:unit` 一次运行 7 个关键测试文件，单 worker，不监听文件；`npm run test:unit:full` 为手动全量入口。浏览器测试不随单元测试启动，默认浏览器命令只验证新建/导入/雪花流程，完整浏览器验证用 `npm run test:e2e:full`，仍串行。详见 `../../docs/design/NOVEL_AGENT_TEST_PLAN.md`。

按改动风险选择定向验证，只有发布前或跨模块修改需要完整回归。不得把默认轻量通过描述为全量通过，也不要因旧测试失败而删除仍有效的保护边界。已移除退役合同、创作准备、脚手架、旧审稿 UI 测试；第二轮再精简重复适配器、简单转发、本地模板固定结果与旧 IDEA 创建/合同等待用例。原文证据、权限、正文复制与保存、版本冲突和自动循环仍保留；公共协议在公共层验证，避免每个业务适配器重复断言。具体删除范围和残余覆盖见测试计划。

需要覆盖的边界（可用定向测试验证）：

第三轮精简已移除提示词文学关键词/固定句子的回归断言，不再要求改一句措辞就更新测试。保留输入内容传递、协议解析、模板资源/权限、证据和预算等行为验证。前端旧合同模拟与重复尺寸/截图已清理，浏览器仅失败留图。不要把这些精简反向解释为删除生产提示词或废止业务权限；详情见测试计划。

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

- 无调用代码清理（2026-10-19）：移除仅被测试使用的 ChapterPlan.withSceneOutline，测试直接构造当前 content 文字块，旧 JSONB 读取与过渡构造器保留。前端删除无页面调用的旧审稿/正史提交、项目级模型设置、独立规划片段操作及其他闲置请求封装，移除未使用的 Pinia 初始化与依赖；创建及发布 API 测试改走当前 from-story 与 publish 入口。后端 HTTP 兼容接口、历史表、迁移、活动模型调用与作者门禁不变。

- 退休模板清理：移除 AgentPromptDefaults 内无活动调用的合同生成、合同审阅、独立人物补全分支和专用前章常量，以及 story_bible / import_reverse_bible_adapt / import_reverse_bible_continue 三份旧默认资源。活动目录的 20 份阶段模板、3 份会话角色资源及其内容不变，默认发布号不变；退休 key 不再返回默认指令。历史配置/修订记录、合同 410 兼容接口、StoryBibleContent / Schema 和雪花第7步资料投影仍保留。提示词数据库回归改用现行雪花与导入大纲流程验证会话轮转及用户隔离。

- 无调用遗留清理：旧项目模型设置的 CodexModelChoice.from/asSetting、创作准备的 CharacterNameService.initializeFromBlueprints、StoryBibleContent.withCharacterBlueprints、ChatGptTransportService.codexOAuth、未调用的开篇/修订提示词辅助方法及 DraftEditorialPrompts 别名已移除。CREATION_PREPARATION_WORLD 的默认指令分支本就被前置拒绝规则拦截，连同专用常量清除；活动资源、默认发布号、历史模型设置 JSON、有效兼容 HTTP 接口和蓝图序列化不变。Spring 回调、配置绑定 setter、持久化访问器不能仅凭静态引用少就删除。

- 导入与雪花职责：ImportedStoryBlueprintService 只协调来源、雪花运行与创作意图收尾；SQL 状态认领/成功/失败写入 ImportedPlanningStore，来源读取与快照复核集中到 ImportedPlanningSources，继续与确认复用同一判断并保留各自业务门禁。SnowflakePlanningService 负责阶段顺序、保存及恢复；SnowflakeStageGenerator 负责单步 Prompt 和生成，使用注入的 ObjectMapper 返回文本与可选第7步投影，不写运行状态。不在模型调用外包长事务；状态记录失败以 suppressed 保留，不覆盖最初生成错误。九步顺序、请求参数、提示词文本、纯文本协议和数据库表不变。

- 规划代码精简（2026-10-11）：移除退休的独立圣经生成/发布用例、供应商生成器、解析器、导入反推圣经链路、人物补全用例、故事方向实体及仓储。雪花第7步只保留 CharacterDesignService.designPlanning；风格推荐改由 ConfirmedSnowflakeService 按项目和确切版本只读查询。续写的 OCCURRED/PLANNED 归一化归属 ImportedOutlineGenerator，不再依赖旧导入编排。资料快照的 StoryBibleContent/Version/OutputSchema 仍有活动调用，不能仅凭旧名称删除。数据库表、迁移、资料同步、检索、输出协议及模型调用次数不变。

- 写作链路精简（2026-10-09）：已删除不可达的合同生成/合同审阅 Graph、模型调用、Prompt 构造、Schema、Parser 和本地合同模板；前端未使用的合同 API 与版本类型同步移除。活动写作 Graph 复用项目/圣经/供应商输入校验，正文审稿与质量检查复用问题 Schema 构造，保留各自严重级别和类别限制。旧接口仍校验项目权限并返回 410，错误构造集中在 ChapterContractService；章节写作依据、历史读取、持久化字段、活动提示词与正史权限不变。不要将 ChapterContractContent 仅凭名称视为死代码，它仍承载从大纲得到的写作依据。

- `CodexAppServerClient` 负责较多协议与进程细节，需要在补足回归测试后再拆，不要无测试重写。
- 写作用例已拆分，模型公共生命周期由 AbstractModelExecutor 收敛，入口差异分配给四个适配器；公共调用代码已归入 modelaccess，ModelProvider 已进入公共领域包；业务模块通过调用端口访问。其他部分领域对象仍依赖 application 枚举、应用用例沿用 API DTO。本轮未进行全模块纯领域化。重复项目归属校验已统一到 ProjectAccessService；锁定、来源复核、编辑版本和作者门禁仍由各业务用例管理，不统一成通用 CRUD。
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
- 修改 Agent 阶段、Prompt、输入信息或输出 Schema：更新 `../../docs/design/NOVEL_AGENT_STAGES_AND_PROMPTS.md`。
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
- 自动任务验证：领域状态机、版本关联决策、编排服务和 Controller 单元测试；`NOVEL_DB_TEST=true` 时运行全部 `@DatabaseTest`（含 `AutomationPersistenceTest`），验证真实 PostgreSQL、幂等/权限和本地模板两章完整链路，使用独立随机 Schema 并在结束后清理；测试关闭 Kafka 消费并替换 Outbox 发布与 Embedding 定时回填。

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
