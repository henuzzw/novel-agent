# 小说 Agent（novel-agent）

面向长篇小说创作的 Java + Vue 工作台。系统以**作者可控**为前提：模型负责生成候选，作者确认后才进入正式版本；已发布正文经整理后成为“正史”记忆，供后续章节检索与一致性检查。

```text
创作意图 / 导入原作 → 雪花规划与故事圣经 → 分层大纲 → 章节正文 → 检查与润色 → 发布 → 正史记忆（事实、关系、伏笔）
```

## 仓库结构

```text
apps/server/    Spring Boot 后端（Java 21）
apps/web/       Vue 3 + TypeScript 前端
scripts/        本地启动、格式化与测试脚本
evaluation/     写作质量评测语料与说明
docs/           设计、功能说明、运维与路线文档（导航见 docs/README.md）
```

技术栈：Spring Boot 3.5、Spring AI / Spring AI Alibaba Agent Graph、Spring Data JPA、Flyway、PostgreSQL 16 + pgvector、Neo4j、Kafka（Outbox 投递）；前端 Vue 3、Vite、Vue Router、TanStack Query。

## 快速启动

1. 准备 Java 21、Node.js、PostgreSQL（含 pgvector）与 Neo4j。
2. 以 `.env.example` 为模板创建 `.env.local`，填写数据库与模型连接信息（`.env.local` 不入库）。
3. 从仓库根目录启动前后端：`.\scripts\run-all.ps1`（PowerShell）或 `bash scripts/run-all.sh`（Git Bash）。
4. 打开前端 `http://localhost:5173`；后端默认 `http://localhost:8081`，健康检查 `/actuator/health`。

详细步骤、测试命令和配置安全说明见 [docs/README.md](docs/README.md#本地启动)。

## 后端模块地图

后端是单个 Spring Boot 应用，代码位于 `apps/server/src/main/java/com/novelagent/`，**先按业务模块分包，模块内再分层**。每个模块的 `package-info.java` 写有职责、入口和依赖说明，IDE 中打开包即可查看。

| 模块 | 负责内容 | 从这里开始看 |
| --- | --- | --- |
| `project` | 小说项目、创作意图、创作策略与边界、全局模型设置、项目访问权限 | `ProjectController`、`ProjectAccessService` |
| `planning` | 雪花规划、确认资料快照、大纲、规划分块 | `OutlineService`、`ConfirmedSnowflakeService` |
| `writing` | 正文版本、质量检查与润色、写作风格与试写、单章自动编辑循环 | `WritingController`、`ManuscriptService` |
| `canon` | 发布正文、正史提交、类型化事实投影、人物档案、发布后记忆整理 | `CanonPublicationService`、`CanonCommitService` |
| `memory` | 记忆召回、混合检索、Token 预算、Embedding | `NovelMemoryService` |
| `agent` | 章节推进自动化、模型运行记录与任务查询、生成停止 | `AutomationService`、`AgentRunRecorder` |
| `ingest` | 导入已有作品、原文解析、反推规划 | `WorkImportService`、`ImportAnalysisRunner` |
| `chat` | 项目创作对话与修改候选 | `ProjectChatService` |
| `prompt` | 全局提示词模板、版本与默认目录 | `AgentPromptService` |
| `modelaccess` | 模型调用端口、供应商路由、输出协议、预算、模型目录、认证与会话 | `StructuredModelGateway`、`RoutedStructuredModelGateway` |
| `platform` | 横切能力：HTTP 响应工具、异常映射、请求日志、通用工具（JSON、哈希、文本、图状态） | `ApiResponses`、`platform/support` |

### 模块内分层

| 目录 | 职责 | 规则 |
| --- | --- | --- |
| `api/` | HTTP 控制器：路由、参数、状态码、ETag 与缓存头 | 不访问数据库，只调用 `application` |
| `api/dto/` | 请求与响应对象（`*Request`、`*Response`），即 HTTP 接口契约 | 只做数据承载与简单转换 |
| `application/` | 用例编排：权限校验、事务边界、版本冲突检查、工作流 | 业务流程的主要入口 |
| `domain/` | 实体、值对象与状态转换规则 | 不依赖控制器或存储适配器 |
| `infrastructure/` | JPA 仓储、SQL、模型协议客户端、消息与外部接口 | 封装技术细节 |

依赖方向：`api → application → domain`，`infrastructure` 为 `application` 提供实现。分层约束由 `ArchitectureTest` 自动检查。

### 一次请求如何经过各层：生成大纲

```text
POST /api/v1/projects/{projectId}/outlines/actions/generate
  planning/api/OutlineController              解析请求、设置 ETag/Location
  → planning/application/OutlineService        校验项目归属、已发布圣经与字数预算
  → planning/application/OutlineGenerationWorkflow   Agent Graph：输入校验 → 生成 → 输出校验
  → planning/infrastructure/StructuredOutlineGenerator  按所选模型（Codex / DeepSeek）组装提示词
  → modelaccess/application/StructuredModelGateway     业务模块依赖的调用接口
  → modelaccess/infrastructure/RoutedStructuredModelGateway  选择执行器，组装协议与预算
  → *Executor（LegacyCodex / ProjectCodex / ChatGptHttp / DeepSeek）  调用模型并记录运行
  ← OutlineService 保存 OutlineVersion 草稿（作者确认发布后才成为当前大纲）
```

其他生成类功能（规划、正文、质量检查等）基本是同样的链路。

模型供应商与会话策略位于 `modelaccess/domain`。导入、规划模块通过 `StagePromptContributor` 提供专用规则，模型接入模块不反向依赖这些业务实现。前端接口、数据库枚举值与现有会话标识保持兼容。

PostgreSQL 保存权威正史事实；pgvector 与关键词检索共同召回正文。Neo4j 当前用于异步图谱投影，相关事实召回由 PostgreSQL 的 `RelatedCanonFactsTool` 执行。Kafka 负责投影事件，发布后记忆提取由持久化任务队列和应用内 Worker 执行。

## 开发与测试

- 开发前阅读 [服务端 AI 开发上下文](apps/server/AGENTS.md) 和 [Java 服务端当前架构](docs/design/NOVEL_AGENT_JAVA_ARCHITECTURE.md)。
- 后端默认测试：`cd apps/server && ./mvnw test`；完整回归：`./mvnw -Pfull-tests test`（数据库测试需 `NOVEL_DB_TEST=true`）。
- 前端：`cd apps/web && npm run type-check && npm run test:unit && npm run build`。
- 控制器与服务类使用 google-java-format（AOSP 风格），见 `scripts/format-server.ps1`。

## 文档

- [文档导航](docs/README.md)：设计、功能说明、运维和路线文档的分类目录与推荐阅读顺序。
- [功能更新记录](docs/CHANGELOG.md)
- [第三方许可声明](THIRD_PARTY_NOTICES.md)
