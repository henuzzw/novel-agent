# 小说 Agent 文档

面向长篇小说创作的 Java + Vue 工作台。系统以作者可控为前提，逐步完成创作意图、大纲、章节生成、正史记忆、关系图谱和一致性审查。

第一次接触项目，建议按下面顺序阅读：

1. [项目 README](../README.md)：项目是什么、仓库结构、后端模块地图和一次请求如何经过各层。
2. [Java 服务端当前架构](design/NOVEL_AGENT_JAVA_ARCHITECTURE.md)：模块职责、分层规则、事务与架构回归测试。
3. [领域模型](design/NOVEL_AGENT_DOMAIN_MODEL.md) 与 [工作流设计](design/NOVEL_AGENT_WORKFLOW_DESIGN.md)：核心概念（圣经、大纲、正文、正史）及创作流程。
4. [Agent 拓扑流程图](design/NOVEL_AGENT_AGENT_TOPOLOGY.md)：23 种模型工作流的先后关系与作者确认门禁。
5. [服务端 AI 开发上下文](../apps/server/AGENTS.md)：当前实现细节、近期路线与文档同步约定（开发前必读）。

功能演进记录见 [功能更新记录](CHANGELOG.md)。

## 文档目录

| 目录 | 内容 |
| --- | --- |
| [design/](design/) | 产品需求、系统与领域设计、API、AI 规格、提示词、UI 与测试计划 |
| [features/](features/) | 各功能的交付说明：行为、边界、接口和迁移 |
| [ops/](ops/) | 部署、日志与数据库诊断 |
| [roadmap/](roadmap/) | 开发进度、待办与参考项目融合计划 |
| [samples/](samples/) | 示例小说正文、单个项目的修订报告与外部工具日志，仅供参考 |

### 设计（design/）

- [Java + Vue Agent 项目技术栈方案](design/AGENT_TECH_STACK.md)
- [Novel Agent：23 个模型工作流拓扑](design/NOVEL_AGENT_AGENT_TOPOLOGY.md)
- [小说 Agent AI 能力规格](design/NOVEL_AGENT_AI_SPEC.md)
- [Novel Agent 全部提示词汇编](design/NOVEL_AGENT_ALL_PROMPTS.md)
- [小说 Agent API 设计](design/NOVEL_AGENT_API_DESIGN.md)
- [小说 Agent 领域模型设计](design/NOVEL_AGENT_DOMAIN_MODEL.md)
- [Java 服务端当前架构](design/NOVEL_AGENT_JAVA_ARCHITECTURE.md)
- [小说 Agent 长篇记忆与一致性实现方案](design/NOVEL_AGENT_LONG_FORM_MEMORY_IMPLEMENTATION.md)
- [模型请求架构与资料去重](design/NOVEL_AGENT_MODEL_EXECUTION.md)
- [小说 Agent MVP 产品需求文档](design/NOVEL_AGENT_MVP_REQUIREMENTS.md)
- [Novel Agent 简化创作流程](design/NOVEL_AGENT_SIMPLIFIED_WORKFLOW.md)
- [小说 Agent 阶段与 Prompt 设计](design/NOVEL_AGENT_STAGES_AND_PROMPTS.md)
- [小说 Agent 系统设计方案](design/NOVEL_AGENT_SYSTEM_DESIGN.md)
- [小说 Agent 测试计划](design/NOVEL_AGENT_TEST_PLAN.md)
- [小说 Agent UI 规格](design/NOVEL_AGENT_UI_SPEC.md)
- [小说 Agent 工作流设计](design/NOVEL_AGENT_WORKFLOW_DESIGN.md)

### 功能说明（features/）

- [章节推进与自动发布](features/NOVEL_AGENT_CHAPTER_ADVANCE.md)
- [本章创作资料与混合检索](features/NOVEL_AGENT_CHAPTER_RETRIEVAL.md)
- [人物档案整合](features/NOVEL_AGENT_CHARACTER_DOSSIER.md)
- [ChatGPT OAuth 直连接入](features/NOVEL_AGENT_CHATGPT_DIRECT_ACCESS.md)
- [Codex 项目会话与上下文压缩](features/NOVEL_AGENT_CODEX_PROJECT_SESSION.md)
- [已确认雪花规划替代故事方向与独立圣经](features/NOVEL_AGENT_CONFIRMED_SNOWFLAKE.md)
- [创作准备与剧情单元复核交付说明](features/NOVEL_AGENT_CREATION_PREPARATION.md)
- [世界设定与改编幅度](features/NOVEL_AGENT_CREATIVE_BOUNDARIES.md)
- [单章自动写作与检查](features/NOVEL_AGENT_DRAFT_EDITORIAL_LOOP.md)
- [生成状态交付说明](features/NOVEL_AGENT_GENERATION_STATUS.md)
- [生成任务停止](features/NOVEL_AGENT_GENERATION_STOP.md)
- [原文统一解析与改编／续写交付说明](features/NOVEL_AGENT_IMPORT_ANALYSIS.md)
- [正文草稿版本对比](features/NOVEL_AGENT_MANUSCRIPT_COMPARE.md)
- [项目创作对话](features/NOVEL_AGENT_PROJECT_CHAT.md)
- [全局提示词管理](features/NOVEL_AGENT_PROMPT_MANAGEMENT.md)
- [已完成正文一键导出](features/NOVEL_AGENT_PUBLISHED_EXPORT.md)
- [发布后记忆整理与默认接受](features/NOVEL_AGENT_PUBLISHED_MEMORY.md)
- [雪花渐进规划实现](features/NOVEL_AGENT_SNOWFLAKE_PLANNING.md)
- [项目 Token 统计](features/NOVEL_AGENT_TOKEN_USAGE.md)

### 运维（ops/）

- [数据库只读诊断：2026-10-16](ops/NOVEL_AGENT_DATABASE_AUDIT_2026-10-16.md)
- [小说 Agent 部署与运行设计](ops/NOVEL_AGENT_DEPLOYMENT.md)
- [HTTP 请求日志](ops/NOVEL_AGENT_HTTP_LOGGING.md)

### 路线与调研（roadmap/）

- [小说 Agent 自动化能力待办](roadmap/NOVEL_AGENT_AUTONOMOUS_TODO.md)
- [小说 Agent 开发任务进度](roadmap/NOVEL_AGENT_DEVELOPMENT_PROGRESS.md)
- [创作融合集成交付记录](roadmap/NOVEL_AGENT_REFERENCE_INTEGRATION_DELIVERY.md)
- [Novel Agent 创作融合开发计划](roadmap/NOVEL_AGENT_REFERENCE_INTEGRATION_DEVELOPMENT_PLAN.md)
- [Novel Agent 创作流程与提示词融合产品方案](roadmap/NOVEL_AGENT_REFERENCE_INTEGRATION_PRODUCT_PLAN.md)
- [写作 Skill 调研与接入结论](roadmap/NOVEL_AGENT_WRITING_SKILL_RESEARCH.md)

许可声明见仓库根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 技术栈

- Java 21、Spring Boot 3.5、Spring AI 1.1、Spring AI Alibaba 1.1.2.2。
- Spring Data JPA、Flyway。
- PostgreSQL 16、pgvector、Neo4j。
- Vue 3、TypeScript、Vite、Vue Router、Pinia、TanStack Query。
- Vitest、Playwright、Spring Boot Test。

## 目录

```text
apps/server/    Spring Boot 后端（按业务模块分包，见根目录 README 的模块地图）
apps/web/       Vue 3 前端
scripts/        本地启动脚本
evaluation/     写作质量评测语料与说明
docs/           设计、功能说明、运维与路线文档
```

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

4. 打开前端日志显示的地址，默认是 `http://localhost:5173`；端口被占用时 Vite 会自动选择下一端口。后端默认运行在 `http://localhost:8081`。前端通过保留原始 Host/Origin 的代理访问后端，切换端口无需放宽后端 CORS；直接跨域访问后端时仍需配置 `APP_CORS_ALLOWED_ORIGINS`。

通过 SSH 或反向代理访问远程端口时，若转发改写 Host 导致接口返回 `403 Invalid CORS request`，可把实际页面来源加入 `.env.local` 的 `APP_CORS_ALLOWED_ORIGINS` 并重启后端。临时调试也可设置 `APP_CORS_ALLOWED_ORIGINS=*`，允许所有 IP、域名和端口；不启用跨域 Cookie 凭据。当前系统使用固定开发身份、尚无正式登录鉴权，通配符不是访问控制，勿将调试入口长期公开；生产环境使用精确来源及正式鉴权。

## 验证

```powershell
cd apps\server
.\mvnw.cmd test

cd ..\web
npm run type-check
npm run test:unit
npm run build
```

默认测试已缩减为低负载关键回归：后端 9 个测试类、前端 7 个测试文件，均串行执行，前端不进入监听模式。数据库、模拟长时间超时和浏览器完整验证不默认运行。

需要完整回归时手动执行后端 `./mvnw.cmd -Pfull-tests test`、前端 `npm run test:unit:full` 或 `npm run test:e2e:full`。不要同时运行这些命令；详细规则见 [测试计划](design/NOVEL_AGENT_TEST_PLAN.md)。

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
