# 小说 Agent

面向长篇小说创作的 Java + Vue 工作台。系统以作者可控为前提，逐步完成创作意图、大纲、章节生成、正史记忆、关系图谱和一致性审查。

## 当前进度

四条可运行链路已经完成：

- 创建小说项目，支持“故事想法”“已有作品”“大纲与设定”三种入口。
- 保存创作意图、类型、基调、主角、核心冲突和目标字数。
- 项目列表与项目工作台。
- 从创作意图生成三个差异化故事方向，支持调整要求、重新生成和版本留存。
- 故事方向生成已由 Spring AI Alibaba Agent Graph 编排输入校验、模型调用和输出校验。
- 故事方向可选择服务端 Codex、DeepSeek 或本地模板；Codex 通过后端主机上的长驻 App Server 运行，DeepSeek 使用 Responses API。
- Codex 与 DeepSeek 共用 JSON Schema 结构约束，模型结果仍经过 Java 领域校验后才能保存。
- 比较候选的核心冲突、主角弧光、结构、结局、优势与风险，并确认一个方向。
- 根据最近一次已确认方向生成故事圣经，支持服务端 Codex、DeepSeek 和本地模板。
- 故事圣经生成由独立的 Spring AI Alibaba Agent Graph 编排，并使用统一 JSON Schema 和领域校验。
- 故事圣经覆盖主题、世界规则、人物、关系、冲突代价、叙事风格、结局与硬约束。
- 作者可编辑并保存草稿；发布后锁定该版本，并记录为项目当前故事圣经。
- 根据已发布故事圣经生成全书、卷/幕、章节三级大纲，支持三种模型、草稿编辑与发布。
- 字数规划采用模糊容量范围：默认整书允许目标字数上下约一万字浮动，单章仅提供参考区间。
- 从已发布大纲选择章节，生成、编辑并确认章节合同；合同保存来源大纲版本，确认后锁定。
- 根据已确认章节合同生成正文草稿，支持服务端 Codex、DeepSeek 和本地模板。
- 正文支持编辑、保存新版本和作者确认；作者确认不会直接推进正史，需等待审稿与记忆抽取。
- 写作工作台按卷展示章节，并在章节合同与正文草稿间切换。
- 对作者已确认正文执行一致性审稿，输出带证据的问题清单和候选事实。
- 章节合同、正文和审稿也已接入 Spring AI Alibaba Agent Graph，六个标准创作模型阶段统一执行输入校验、模型生成和输出校验。
- 阻断问题必须标记为已处理，候选事实必须逐项接受或拒绝，才能确认审稿门禁。
- 审稿支持服务端 Codex、DeepSeek 和本地规则模板；确认审稿仍不会自动推进正史。
- 已确认审稿可原子提交正史：正文、接受事实、正史版本和 Outbox 在同一个 PostgreSQL 事务中保存。
- Outbox 通过 Kafka 发布纯 ID 与版本事件，正文和事实内容不进入 Kafka 消息。
- pgvector 与 Neo4j 使用独立消费组异步投影，并以事件检查点保证重复消息不会重复写入。
- 提供投影状态接口，可区分 Kafka 已发布、pgvector 已完成和 Neo4j 已完成。
- PostgreSQL 业务数据、JSONB 与 pgvector 基础环境。
- Neo4j 驱动接入和健康检查。
- Flyway 数据库迁移、参数校验、统一异常处理和乐观锁基础。
- 桌面端与移动端响应式界面。
- 支持从故事圣经识别人名并配置正式姓名、昵称和称谓；人物使用稳定 ID，改名后规划、正文预览与导出自动显示新名字。

长期记忆链路已经接入章节合同、正文和审稿生成：系统通过阶段白名单执行近期章节摘要、pgvector 语义历史检索和 Neo4j 相关正史事实三个只读工具。召回内容采用“摘要优先、正文片段补充”的分层结构；合同、正文、审稿默认分别使用 5000、8000、6000 Token，并根据模型上下文容量动态收缩。Embedding 统一使用 1024 维接口：默认本地特征向量便于开发，也可切换到兼容 OpenAI `/embeddings` 的正式语义模型，并自动回填旧文档。

工作台现已支持 TXT、Markdown、DOCX、文本型 PDF 导入，以及项目级 Agent 任务与成本页面。确认导入后可选择“故事素材改编”或“已有正文续写”：素材改编允许扩写、重构和改变，小说从第一章重新创作；正文续写则明确标记“已发生”和“待规划”章节。两种模式生成的故事圣经和大纲都保持草稿，需作者检查和发布。Neo4j 投影包含稳定实体、事件、状态变化、关系、知识边界、伏笔和项目版本水位。

可通过 `GET /api/v1/projects/{projectId}/memory/preview?chapterNumber=2&query=检索内容` 检查当前章节能够召回的历史正文与正史事实。

## 技术栈

- Java 21、Spring Boot 3.5、Spring AI 1.1、Spring AI Alibaba 1.1.2.2。
- Spring Data JPA、Flyway。
- PostgreSQL 16、pgvector、Neo4j。
- Vue 3、TypeScript、Vite、Vue Router、Pinia、TanStack Query。
- Vitest、Playwright、Spring Boot Test。

## 目录

```text
apps/server/    Spring Boot 后端
apps/web/       Vue 前端
scripts/        本地启动脚本
*.md            产品、架构、API、AI、UI、测试与部署设计
```

服务端开发前先阅读 [服务端 AI 开发上下文](apps/server/AGENTS.md)，其中记录当前实现、核心链路、近期路线和文档同步约定。

## 本地启动

准备 Java 21、Node.js 和 npm。后端包含 Maven Wrapper，不要求全局安装 Maven。

1. 以 `.env.example` 为模板创建 `.env.local`，填写 PostgreSQL、Neo4j 和 DeepSeek 连接信息。
2. 使用服务端 Codex 时，先确认后端进程用户已登录 Codex CLI；后端会管理 `codex app-server` 长驻进程并复用原生 thread。
3. 在 PowerShell 中从项目根目录同时启动前后端：

```powershell
.\scripts\run-all.ps1
```

   如果使用 Git Bash，也可以运行：

```bash
bash scripts/run-all.sh
```

   PowerShell 中的 `bash` 可能指向 WSL；运行 `.sh` 时请打开 Git Bash。两个服务的日志会显示在同一个终端。已有的单独启动脚本仍可用于只运行某一端：

```powershell
.\scripts\run-server.ps1
.\scripts\run-web.ps1
```

4. 打开 `http://localhost:5173`。后端默认运行在 `http://localhost:8081`。

## 验证

```powershell
cd apps\server
.\mvnw.cmd test

cd ..\web
npm run type-check
npm run test:unit -- --run
npm run build
```

服务启动后可通过 `http://localhost:8081/actuator/health` 查看 PostgreSQL、Neo4j 和应用状态。

类型化正史只读接口：

- `GET /api/v1/projects/{projectId}/canon/entities`：人物、地点、物品等实体。
- `GET /api/v1/projects/{projectId}/canon/timeline`：已确认事件时间线。
- `GET /api/v1/projects/{projectId}/canon/entities/{entityId}/state`：实体当前状态及证据。
- `GET /api/v1/projects/{projectId}/canon/foreshadows`：伏笔状态。
- `GET /api/v1/projects/{projectId}/canon/entities/{entityId}/aliases`：实体的确认别名。
- `POST /api/v1/projects/{projectId}/canon/entities/{entityId}/aliases`：登记昵称、称号或旧名。
- `GET /api/v1/projects/{projectId}/canon/entities/{entityId}/mentions`：查看实体名称解析记录。

## 配置安全

`.env.local` 已被 Git 忽略。数据库密码、模型密钥和其他敏感配置只放在本地环境或部署平台的密钥管理中，不写入源码和文档。

## 设计文档

- [系统设计](NOVEL_AGENT_SYSTEM_DESIGN.md)
- [MVP 需求](NOVEL_AGENT_MVP_REQUIREMENTS.md)
- [领域模型](NOVEL_AGENT_DOMAIN_MODEL.md)
- [API 设计](NOVEL_AGENT_API_DESIGN.md)
- [工作流设计](NOVEL_AGENT_WORKFLOW_DESIGN.md)
- [AI 规范](NOVEL_AGENT_AI_SPEC.md)
- [Agent 阶段与 Prompt](NOVEL_AGENT_STAGES_AND_PROMPTS.md)
- [UI 规范](NOVEL_AGENT_UI_SPEC.md)
- [测试计划](NOVEL_AGENT_TEST_PLAN.md)
- [部署方案](NOVEL_AGENT_DEPLOYMENT.md)
- [长篇记忆与一致性实现方案](NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md)
