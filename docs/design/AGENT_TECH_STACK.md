# Java + Vue Agent 项目技术栈方案

## 1. 项目目标

本文档用于规划一个完整的 Java + Vue 智能 Agent 项目。系统不仅提供基础的 AI 对话能力，还应支持模型管理、流式响应、工具调用、知识库检索、会话记忆、任务编排、权限控制、日志监控和容器化部署。

典型应用场景包括：

- 智能客服与企业知识助手
- 小说创作、文案生成和内容审核
- 数据查询与分析助手
- 自动化办公 Agent
- 支持外部 API、数据库和搜索工具的业务 Agent
- 多步骤任务规划与执行平台

## 2. 总体架构

```text
┌──────────────────────────────────────────────┐
│                 Vue 3 前端                   │
│ 对话界面 / 知识库 / Agent 配置 / 系统管理    │
└──────────────────────┬───────────────────────┘
                       │ REST API / SSE / WebSocket
┌──────────────────────▼───────────────────────┐
│              Spring Boot 后端                │
│ 用户权限 / 会话管理 / Agent 编排 / 文件处理  │
└───────────────┬──────────────┬───────────────┘
                │              │
┌───────────────▼──────┐ ┌─────▼────────────────┐
│ AI 模型与 Agent 框架 │ │ 数据与基础设施       │
│ Spring AI Alibaba    │ │ PostgreSQL / Redis   │
│ LangChain4j          │ │ pgvector / MinIO     │
│ Tool Calling / RAG   │ │ MQ / Elasticsearch   │
└───────────────┬──────┘ └──────────────────────┘
                │
┌───────────────▼──────────────────────────────┐
│ OpenAI / Claude / Gemini / DeepSeek / 通义等 │
└──────────────────────────────────────────────┘
```

## 3. 后端技术栈

### 3.1 Java 基础框架

| 技术 | 建议版本 | 用途 |
| --- | --- | --- |
| Java | 21 LTS | 主要开发语言，支持虚拟线程等新特性 |
| Spring Boot | 3.5.x | 后端应用基础框架，与 Spring AI Alibaba 1.1 稳定线对齐 |
| Spring Web MVC | 与 Spring Boot 对齐 | REST API 和普通 Web 接口 |
| Spring WebFlux | 与 Spring Boot 对齐，可选 | 仅在端到端响应式链路确有收益时使用 |
| Spring Security | 与 Spring Boot 对齐 | 登录认证、接口鉴权和权限控制 |
| OAuth2 Resource Server | 与 Spring Boot 对齐 | JWT 解析及第三方身份认证 |
| Jakarta Validation | 与 Spring Boot 对齐 | 请求参数校验 |
| Spring AOP | 与 Spring Boot 对齐 | 日志、权限、审计等横切逻辑 |
| Lombok | 最新稳定版 | 减少样板代码 |
| MapStruct | 最新稳定版 | DTO、实体和视图对象转换 |

### 3.2 AI 与 Agent 框架

本项目采用 Spring AI Alibaba 作为 Agent 编排主框架，并复用 Spring AI 的模型、提示词、工具调用和结构化输出抽象：

| 框架 | 特点 | 适合场景 |
| --- | --- | --- |
| Spring AI Alibaba 1.1.2.2 | 提供 Agent Framework、Graph 编排和 Spring AI 集成 | 本项目的主要 Agent 框架 |
| Spring AI 1.1.x | 提供标准模型、Prompt、Tool Calling、RAG 等基础抽象 | 标准模型通道与通用 AI 能力 |
| 受控运行时适配器 | 保留供应商原生协议和能力 | Codex App Server、DeepSeek Responses 等尚未完整覆盖的接口 |

项目需要具备的 AI 核心能力：

- 多模型统一接入
- Prompt 模板管理
- 流式对话响应
- Function Calling / Tool Calling
- 结构化输出
- 上下文管理
- 会话短期记忆和长期记忆
- RAG 知识库检索
- 多步骤任务规划与执行
- 模型失败重试、超时与降级
- Token 统计和成本记录
- 内容安全检查

可接入的模型服务：

- OpenAI
- Azure OpenAI
- Anthropic Claude
- Google Gemini
- DeepSeek
- 阿里云通义千问
- 智谱 GLM
- 火山引擎豆包
- Ollama 本地模型
- 兼容 OpenAI API 协议的私有模型服务

### 3.3 API 与接口规范

- RESTful API：常规数据增删改查
- SSE：AI 文本流式输出，聊天场景优先推荐
- WebSocket：需要双向实时通信时使用
- OpenAPI 3 / Swagger UI：接口文档与联调
- 统一响应结构：状态码、消息、数据和请求 ID
- 全局异常处理：统一业务异常和系统异常格式

### 3.4 数据访问

| 技术 | 用途 |
| --- | --- |
| Spring Data JPA | 面向实体的通用数据访问 |
| MyBatis-Plus | SQL 较复杂或需要精细控制时使用 |
| Flyway / Liquibase | 数据库版本迁移 |
| HikariCP | 数据库连接池，Spring Boot 默认方案 |

JPA 和 MyBatis-Plus 一般选择一个作为主要数据访问方案，避免同一模块中混用造成维护成本上升。

## 4. 前端技术栈

### 4.1 核心框架

| 技术 | 用途 |
| --- | --- |
| Vue 3 | 前端核心框架 |
| TypeScript | 类型安全和大型项目维护 |
| Vite | 开发服务器与项目构建 |
| Vue Router | 页面路由 |
| Pinia | 全局状态管理 |
| Axios / Fetch | HTTP 请求 |

### 4.2 UI 与交互

| 技术 | 用途 |
| --- | --- |
| Element Plus | 后台管理系统组件库，推荐首选 |
| Naive UI / Ant Design Vue | 可选 UI 组件库 |
| markdown-it | 渲染模型返回的 Markdown |
| Shiki / highlight.js | 代码语法高亮 |
| Mermaid | 流程图、时序图等内容渲染 |
| ECharts | Token 用量、调用量和任务统计图表 |
| Monaco Editor | Prompt、JSON Schema 或代码编辑器 |
| Tiptap | 富文本内容编辑，可选 |

### 4.3 前端关键能力

- 聊天消息流式展示
- 停止生成和重新生成
- Markdown、代码块和表格渲染
- 会话列表及历史消息管理
- 文件上传、解析状态和进度展示
- 知识库管理与文档预览
- Agent、模型、Prompt 和工具配置
- 工具调用过程与结果展示
- 长任务状态展示和失败重试
- Token 用量及成本看板
- 用户、角色和权限管理
- 深色模式和响应式布局

## 5. 数据存储

### 5.1 关系型数据库

优先推荐 PostgreSQL，也可以使用 MySQL。

主要存储内容：

- 用户、角色和权限
- Agent 配置
- 模型配置
- Prompt 模板及版本
- 会话和消息记录
- 知识库及文档元数据
- 工具定义及调用记录
- 任务状态与执行步骤
- Token 消耗和费用统计
- 系统配置与审计日志

### 5.2 缓存与分布式状态

使用 Redis 处理：

- 登录状态和 Token 黑名单
- 热点配置缓存
- 对话临时状态
- 分布式锁
- 接口限流
- 短期记忆
- 异步任务进度

### 5.3 向量数据库

| 方案 | 特点 |
| --- | --- |
| pgvector | 与 PostgreSQL 共用数据库，部署简单，首版推荐 |
| Milvus | 适合大规模向量检索 |
| Qdrant | API 友好，部署和过滤能力较好 |
| Elasticsearch / OpenSearch | 适合全文检索与向量混合检索 |
| Weaviate | 提供完整的向量数据管理能力 |

首版项目建议使用 PostgreSQL + pgvector，数据规模扩大后再评估独立向量数据库。

### 5.4 对象存储

使用 MinIO、Amazon S3、阿里云 OSS 或腾讯云 COS 存储：

- 用户上传的原始文件
- 知识库文档
- 图片、音频和视频
- AI 生成文件
- 导出结果与备份文件

## 6. RAG 知识库技术栈

完整的知识库处理链路如下：

```text
文件上传
  → 文件解析
  → 文本清洗
  → 文本分块
  → Embedding 向量化
  → 向量数据库入库
  → 用户问题向量化
  → 相似度检索
  → 可选重排序
  → 组装上下文
  → 大模型生成答案
  → 返回引用来源
```

推荐组件：

| 技术 | 用途 |
| --- | --- |
| Apache Tika | 通用文档内容提取 |
| Apache PDFBox | PDF 文本处理 |
| Apache POI | Word、Excel、PowerPoint 解析 |
| OCR 服务 | 扫描版 PDF 和图片文字识别 |
| Embedding 模型 | 将文本转换为向量 |
| Reranker 模型 | 对初步召回结果重新排序 |

RAG 实现时需要重点关注：

- 文本分块大小和重叠长度
- 文档标题、章节等元数据保留
- 向量检索与关键词检索的混合召回
- 数据权限过滤
- 检索结果重排序
- 回答引用和原文定位
- 文档更新、删除后的向量同步
- 无可靠资料时避免模型编造答案

## 7. Agent 工具系统

Agent 可以通过 Tool Calling 调用业务能力，常见工具包括：

- 数据库查询工具
- HTTP API 调用工具
- 企业内部系统工具
- 搜索工具
- 文件读写和文档生成工具
- 邮件、消息通知和工单工具
- 日期、计算器和代码执行工具
- 工作流启动和任务状态查询工具

工具系统应提供：

- 工具名称、描述和参数 Schema
- 工具启用与禁用
- 用户和角色授权
- 参数校验
- 超时、重试和熔断
- 敏感操作二次确认
- 调用日志与审计
- 返回内容大小限制
- 沙箱及访问范围限制

不要让模型直接执行未经校验的 SQL、系统命令或高风险外部操作。

## 8. 异步任务与消息队列

简单项目可以先使用 Spring 异步任务和定时任务。出现大量文档解析、向量化、批量生成或长时间 Agent 任务后，可引入消息队列。

可选方案：

- RabbitMQ：业务任务队列，使用简单
- Kafka：高吞吐事件流和日志场景
- RocketMQ：复杂事务消息和国内生态
- XXL-JOB：分布式定时任务
- Spring Scheduler：单体项目定时任务

适合异步执行的任务包括：

- 文档解析与向量化
- 大批量内容生成
- 数据同步
- 邮件和消息通知
- 长时间 Agent 工作流
- 统计数据汇总

## 9. 安全与权限

建议采用 RBAC 权限模型：用户、角色、权限、资源。

安全措施包括：

- JWT Access Token + Refresh Token
- OAuth2 / OIDC 单点登录，可选
- API 接口权限控制
- Agent、知识库和工具级数据权限
- Prompt 注入防护
- 上传文件类型、大小和内容校验
- 敏感字段加密存储
- API Key 加密及脱敏展示
- 接口限流与防重复提交
- 高风险工具调用人工确认
- 操作日志和审计日志
- 模型输入、输出的内容安全审核
- 前端防 XSS，尤其是 Markdown 和 HTML 渲染

## 10. 日志、监控与可观测性

| 技术 | 用途 |
| --- | --- |
| Logback / SLF4J | Java 应用日志 |
| ELK / OpenSearch | 日志集中检索 |
| Loki + Grafana | 轻量日志查看与可视化 |
| Prometheus | 指标采集 |
| Grafana | 指标看板和告警 |
| OpenTelemetry | 链路、指标和日志标准化 |
| SkyWalking / Jaeger | 分布式链路追踪 |

建议监控以下 AI 指标：

- 模型请求次数和成功率
- 首 Token 响应时间
- 完整响应耗时
- 输入和输出 Token 数
- 模型调用成本
- 限流、超时和重试次数
- 工具调用成功率
- RAG 检索耗时与命中情况
- Agent 任务成功率和平均步骤数

日志中不应直接记录完整 API Key、密码、身份证号、手机号或其他敏感内容。

## 11. 测试技术栈

### 后端测试

- JUnit 5：单元测试
- Mockito：依赖模拟
- Spring Boot Test：集成测试
- Testcontainers：启动真实 PostgreSQL、Redis 等测试环境
- WireMock：模拟外部模型和 HTTP 服务
- REST Assured：API 自动化测试，可选

### 前端测试

- Vitest：单元测试
- Vue Test Utils：Vue 组件测试
- Playwright：端到端测试

### AI 功能测试

- 固定测试数据集和预期结果
- Prompt 回归测试
- RAG 召回率与答案正确性评估
- 工具选择和参数正确性测试
- 幻觉率、拒答率及安全测试
- 不同模型之间的效果、速度和成本对比

AI 输出具有不确定性，测试时应优先校验结构、关键事实、引用来源和安全边界，避免只比较完整文本是否一致。

## 12. 工程化与部署

### 12.1 开发工具

- Maven 或 Gradle：Java 依赖和构建管理
- npm、pnpm 或 yarn：前端依赖管理，推荐 pnpm
- Git：版本控制
- ESLint + Prettier：前端代码规范
- Checkstyle / Spotless：Java 代码格式检查
- Husky + lint-staged：提交前检查，可选

### 12.2 容器与网关

- Docker：应用容器化
- Docker Compose：本地开发和中小规模部署
- Kubernetes：大规模生产部署，可后续引入
- Nginx：静态资源、反向代理和 HTTPS
- Traefik / Spring Cloud Gateway：复杂网关场景可选

### 12.3 CI/CD

可使用 GitHub Actions、GitLab CI、Jenkins 或企业内部流水线，执行：

1. 代码检查
2. 单元测试
3. 前后端构建
4. Docker 镜像构建
5. 镜像安全扫描
6. 推送镜像仓库
7. 部署测试或生产环境
8. 健康检查及失败回滚

## 13. 推荐的项目模块

```text
novel-agent/
├── agent-server/              # Spring Boot 后端
│   ├── agent-api/             # 接口、DTO 和统一响应
│   ├── agent-auth/            # 登录、用户、角色和权限
│   ├── agent-core/            # Agent 编排和核心领域逻辑
│   ├── agent-ai/              # 模型接入、Prompt、记忆和工具调用
│   ├── agent-rag/             # 文件解析、分块、向量化和检索
│   ├── agent-tool/            # 工具定义、执行、安全和审计
│   ├── agent-infrastructure/  # 数据库、Redis、MQ 和对象存储
│   └── agent-boot/            # 应用启动与配置
├── agent-web/                 # Vue 3 前端
│   ├── src/api/               # 接口请求
│   ├── src/components/        # 通用组件
│   ├── src/views/             # 页面
│   ├── src/stores/            # Pinia 状态
│   ├── src/router/            # 路由
│   └── src/utils/             # 工具函数
├── deploy/                    # Docker、Nginx 和部署配置
├── docs/                      # 架构与接口文档
└── docker-compose.yml         # 本地完整环境
```

项目初期也可以先采用单体后端，按领域划分包结构；业务稳定且确实出现独立扩缩容需求后，再拆分微服务。

## 14. 首版推荐技术组合

为了控制开发成本，同时保留后续扩展空间，首版建议采用：

### 后端

- Java 21
- Spring Boot 3.5.x
- Spring Web MVC + SSE
- Spring Security + JWT
- Spring AI 1.1.x
- Spring AI Alibaba 1.1.2.2 Agent Framework
- MyBatis-Plus 或 Spring Data JPA
- PostgreSQL + pgvector
- Redis
- Flyway
- MinIO
- OpenAPI 3

### 前端

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Element Plus
- Axios
- markdown-it + Shiki
- ECharts

### 部署

- Docker Compose
- Nginx
- Prometheus + Grafana
- Loki 或 ELK

这套组合可以支撑用户登录、AI 流式对话、知识库、Agent 工具调用、管理后台、调用统计和单机生产部署。

## 15. 分阶段实施建议

### 第一阶段：基础对话

- 用户登录和权限基础
- 模型配置
- AI 流式对话
- 会话和消息持久化
- Prompt 模板
- Token 用量统计

### 第二阶段：知识库 RAG

- 文件上传和对象存储
- 文档解析与分块
- Embedding 和向量检索
- 检索结果引用
- 文档状态和失败重试

### 第三阶段：Agent 与工具调用

- 工具注册和参数 Schema
- Agent 配置
- 多步骤执行
- 工具权限与高风险操作确认
- 调用过程展示和审计

### 第四阶段：生产能力

- 消息队列和异步任务
- 限流、熔断和模型降级
- 日志、指标和链路追踪
- 内容安全
- 自动化测试和 CI/CD
- 多租户或更细粒度数据权限

## 16. 关键选型原则

1. 首版优先使用模块化单体，不要过早拆分微服务。
2. 聊天输出优先使用 SSE，只有明确需要双向实时通信时再使用 WebSocket。
3. 初期优先选择 PostgreSQL + pgvector，减少数据库组件数量。
4. Spring AI 与 LangChain4j 选择一个作为主要抽象层，避免业务代码同时依赖两套框架。
5. 模型调用必须设置超时、重试、并发限制和降级策略。
6. 工具调用必须经过参数校验、权限检查和审计，高风险操作需要人工确认。
7. API Key 和敏感配置必须加密保存，禁止提交到 Git 仓库。
8. RAG 回答应返回引用来源，并允许在资料不足时明确拒答。
9. 先完成可观测的单 Agent 闭环，再考虑多 Agent 协作和复杂工作流。
10. 对模型、向量数据库和对象存储进行接口隔离，方便未来替换供应商。

## 17. 总结

一个完整的 Java + Vue Agent 项目，本质上包含传统业务系统、AI 模型接入、RAG 知识库、工具执行平台和可观测体系五部分。

推荐从以下最小闭环开始：

```text
Vue 3 + Spring Boot + Spring AI
        + PostgreSQL/pgvector
        + Redis + MinIO
        + SSE + Docker Compose
```

在完成稳定的对话、知识检索和工具调用后，再根据实际并发量与业务复杂度引入消息队列、独立向量数据库、工作流引擎、Kubernetes 或多 Agent 架构。
