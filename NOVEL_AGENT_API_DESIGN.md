# 小说 Agent API 设计

> 文档状态：初稿  
> 目标版本：MVP 0.1  
> 需求文档：[NOVEL_AGENT_MVP_REQUIREMENTS.md](./NOVEL_AGENT_MVP_REQUIREMENTS.md)  
> 领域模型：[NOVEL_AGENT_DOMAIN_MODEL.md](./NOVEL_AGENT_DOMAIN_MODEL.md)

## 1. 设计目标

本文定义 Vue 前端与 Spring Boot 后端之间的 HTTP API、异步任务事件、错误格式、并发控制和幂等约定。

API 必须满足：

1. 前端可以完成从创建项目到提交一章正史的完整流程。
2. 长时间 AI 任务不占用同步请求，支持进度、取消、重试和恢复。
3. 草稿、候选事实与正史提交使用不同接口，避免越权写入。
4. 所有修改可检测并发冲突。
5. 重复请求不会产生重复任务、版本或正史提交。
6. 错误响应既适合用户理解，也便于程序恢复。

## 2. API 总体约定

### 2.1 基础路径

```text
/api/v1
```

资源路径使用复数名词和小写连字符：

```text
/api/v1/projects
/api/v1/projects/{projectId}/chapters
/api/v1/agent-runs/{runId}
```

动作只用于无法自然表达为资源变更的命令：

```text
POST /chapters/{chapterId}/actions/generate
POST /canon-commits/actions/preview
POST /agent-runs/{runId}/actions/cancel
```

### 2.2 数据格式

- 请求与响应使用 `application/json; charset=utf-8`。
- 文件上传使用 `multipart/form-data`。
- SSE 使用 `text/event-stream`。
- 字段使用 `camelCase`。
- ID 使用 UUID 字符串。
- 时间使用 ISO 8601 UTC，例如 `2026-09-25T13:20:00Z`。
- 枚举使用大写蛇形格式，例如 `NEEDS_CONFIRMATION`。
- 金额不使用浮点数，模型费用以最小货币单位或高精度字符串返回。
- 大整数版本号使用 JSON number；前端内部不得做算术推断。

### 2.3 通用请求头

| 请求头 | 是否必需 | 用途 |
| --- | --- | --- |
| `Authorization` | 是 | `Bearer <token>` |
| `X-Request-Id` | 否 | 客户端请求追踪 ID；缺失时服务端生成 |
| `Idempotency-Key` | 修改命令按约定必需 | 防止重复创建任务或提交 |
| `If-Match` | 更新资源时必需 | 传递资源版本，例如 `"7"` |
| `Accept-Language` | 否 | 错误消息语言，默认 `zh-CN` |

响应返回：

```text
X-Request-Id: 01K...
ETag: "8"
```

### 2.4 成功响应

单个资源直接返回资源本身，不套无意义的 `data` 外壳：

```json
{
  "id": "0199a4c8-1b31-7b22-a7f4-2f7b949f6331",
  "name": "钟楼来信",
  "status": "ACTIVE",
  "version": 3
}
```

创建资源返回 `201 Created` 和 `Location`。异步命令返回 `202 Accepted`：

```json
{
  "runId": "0199a4d0-8f43-7352-9013-7caaed4375ae",
  "status": "QUEUED",
  "statusUrl": "/api/v1/agent-runs/0199a4d0-8f43-7352-9013-7caaed4375ae",
  "eventsUrl": "/api/v1/agent-runs/0199a4d0-8f43-7352-9013-7caaed4375ae/events"
}
```

### 2.5 分页

普通列表采用游标分页：

```text
GET /api/v1/projects?limit=20&cursor=eyJpZCI6Ii4uLiJ9
```

```json
{
  "items": [],
  "nextCursor": "eyJpZCI6Ii4uLiJ9",
  "hasMore": false
}
```

- `limit` 默认 20，最大 100。
- 游标是服务端生成的不透明字符串。
- 大纲树、章节目录等有界层级资源可以一次返回，不强制分页。

### 2.6 字段选择与展开

MVP 不实现通用 GraphQL 式字段选择。仅对明确资源提供 `include`：

```text
GET /chapters/{chapterId}?include=activeContract,currentDraft
```

服务端必须限制可展开项，避免任意深度加载。

## 3. 错误模型

### 3.1 Problem Details

错误响应采用 RFC 9457 风格的 `application/problem+json`：

```json
{
  "type": "https://novel-agent.local/problems/canon-version-conflict",
  "title": "正史版本冲突",
  "status": 409,
  "code": "CANON_VERSION_CONFLICT",
  "detail": "项目正史已从 12 更新到 13，请刷新差异后重新提交。",
  "instance": "/api/v1/projects/0199.../canon-commits",
  "requestId": "01K...",
  "errors": [
    {
      "field": "expectedCanonVersion",
      "code": "STALE_VERSION",
      "message": "期望版本为 12，当前版本为 13"
    }
  ],
  "meta": {
    "expectedCanonVersion": 12,
    "actualCanonVersion": 13
  }
}
```

### 3.2 核心错误码

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON、枚举或业务格式错误 |
| 400 | `UNSUPPORTED_FILE_TYPE` | 不支持的导入文件 |
| 401 | `UNAUTHENTICATED` | 未登录或令牌失效 |
| 403 | `PROJECT_ACCESS_DENIED` | 无项目权限 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或对当前用户不可见 |
| 409 | `RESOURCE_VERSION_CONFLICT` | `If-Match` 与当前行版本不符 |
| 409 | `CANON_VERSION_CONFLICT` | 正史版本已经推进 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一个幂等键对应了不同请求 |
| 409 | `RUN_STATE_CONFLICT` | 当前任务状态不允许取消或重试 |
| 422 | `CANON_VALIDATION_FAILED` | 提交违反正史不变量 |
| 422 | `UNRESOLVED_BLOCKING_ISSUES` | 存在未处理的阻断级审稿问题 |
| 422 | `PROPOSAL_NOT_DECIDED` | 候选事实尚未全部处理 |
| 422 | `IMPORT_STRUCTURE_INVALID` | 章节拆分或原文结构不合法 |
| 429 | `MODEL_QUOTA_EXCEEDED` | 模型额度或任务频率超限 |
| 500 | `INTERNAL_ERROR` | 未分类服务端错误 |
| 502 | `MODEL_PROVIDER_ERROR` | 模型供应商失败 |
| 503 | `PROJECTION_NOT_READY` | 强一致查询要求的投影尚未完成 |

用户可恢复错误在 `meta` 中提供恢复信息，但不暴露堆栈、密钥、Prompt 或完整正文。

## 4. 身份与项目权限

MVP 最少支持项目所有者权限。接口仍按可扩展权限设计：

```text
OWNER   项目全部权限，包括删除
EDITOR  编辑、生成、审核和提交
VIEWER  只读
```

权限校验必须在服务端完成，不能依赖前端隐藏按钮。对无权访问的项目资源统一返回 `404`，减少项目 ID 枚举风险。

### 4.1 当前用户

```http
GET /api/v1/me
```

```json
{
  "id": "0199a4...",
  "displayName": "作者",
  "roles": ["USER"],
  "preferences": {
    "language": "zh-CN",
    "timezone": "Asia/Shanghai"
  }
}
```

## 5. 项目 API

### 5.1 接口清单

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/projects` | 创建项目 |
| `GET` | `/projects` | 项目列表 |
| `GET` | `/projects/{projectId}` | 项目详情 |
| `PATCH` | `/projects/{projectId}` | 修改项目基本信息 |
| `POST` | `/projects/{projectId}/actions/archive` | 归档项目 |
| `POST` | `/projects/{projectId}/actions/restore` | 恢复项目 |
| `DELETE` | `/projects/{projectId}` | 发起彻底删除任务 |

### 5.2 创建项目

```http
POST /api/v1/projects
Idempotency-Key: 6b87...
Content-Type: application/json
```

```json
{
  "name": "钟楼来信",
  "entryMode": "IDEA",
  "creativeIntent": {
    "premise": "一个转学生被卷入班级秘密",
    "genres": ["青春校园", "群像"],
    "protagonistBrief": "高二女生，安静而敏锐",
    "centralConflict": "她想保持距离，却逐渐成为群体关系的中心",
    "targetWords": 120000,
    "tones": ["轻松", "温暖"]
  }
}
```

返回 `201 Created`。`MANUSCRIPT` 或 `MATERIALS` 入口可以暂不传创作意图，随后进入导入流程。

### 5.3 更新创作意图

```http
PUT /api/v1/projects/{projectId}/creative-intent
If-Match: "3"
```

完整替换创作意图，成功返回新资源和新 `ETag`。重大变化的影响分析通过单独异步命令触发，不在普通更新请求中隐式执行。

## 6. 文件导入 API

### 6.1 接口清单

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/projects/{projectId}/import-batches` | 创建导入批次 |
| `POST` | `/import-batches/{batchId}/files` | 上传文件 |
| `GET` | `/import-batches/{batchId}` | 导入状态与摘要 |
| `GET` | `/import-batches/{batchId}/files` | 文件列表 |
| `GET` | `/import-batches/{batchId}/parsed-units` | 解析结构 |
| `PATCH` | `/import-batches/{batchId}/parsed-units/{unitId}` | 调整章节结构或分类 |
| `POST` | `/import-batches/{batchId}/actions/parse` | 启动解析与抽取 |
| `GET` | `/import-batches/{batchId}/proposals` | 候选事实列表 |
| `PATCH` | `/import-batches/{batchId}/proposals/{proposalId}` | 接受、编辑或拒绝候选 |
| `POST` | `/import-batches/{batchId}/actions/preview-commit` | 预览导入提交 |
| `POST` | `/import-batches/{batchId}/actions/commit` | 提交导入结果 |

### 6.2 创建批次

```json
{
  "purpose": "MANUSCRIPT",
  "options": {
    "detectChapters": true,
    "extractStoryFacts": true,
    "language": "zh-CN"
  }
}
```

返回：

```json
{
  "id": "0199...",
  "projectId": "0199...",
  "purpose": "MANUSCRIPT",
  "status": "UPLOADED",
  "version": 0
}
```

### 6.3 上传文件

```http
POST /api/v1/import-batches/{batchId}/files
Content-Type: multipart/form-data
Idempotency-Key: 8c21...
```

表单字段：

- `file`：文件内容。
- `documentRole`：可选，`AUTO`、`MANUSCRIPT`、`OUTLINE`、`SETTING`、`REFERENCE`。

MVP 默认限制：

- 单文件不超过 50 MB。
- 单批次不超过 20 个文件。
- 支持 `.txt`、`.md`、`.docx`、文本型 `.pdf`。
- 仅根据扩展名不可信，服务端同时检查 MIME 和文件签名。

### 6.4 启动解析

```http
POST /api/v1/import-batches/{batchId}/actions/parse
Idempotency-Key: 53be...
```

返回 `202 Accepted` 和 `runId`。解析、章节识别、分类和事实抽取在同一工作流的不同步骤执行。

### 6.5 调整解析单元

```http
PATCH /api/v1/import-batches/{batchId}/parsed-units/{unitId}
If-Match: "2"
```

```json
{
  "title": "第十八章 钟楼里的密信",
  "unitType": "CHAPTER",
  "ordinal": 18,
  "documentRole": "MANUSCRIPT"
}
```

合并和拆分属于命令接口：

```text
POST /parsed-units/{unitId}/actions/split
POST /parsed-units/actions/merge
```

### 6.6 决策候选事实

```http
PATCH /api/v1/import-batches/{batchId}/proposals/{proposalId}
If-Match: "1"
```

接受：

```json
{
  "decision": "ACCEPTED"
}
```

编辑后接受：

```json
{
  "decision": "EDITED",
  "payload": {
    "displayName": "顾遥",
    "entityType": "CHARACTER",
    "summary": "林澈的同学，隐瞒了密信来源"
  }
}
```

拒绝：

```json
{
  "decision": "REJECTED",
  "reason": "这是角色的误解，不是客观事实"
}
```

候选响应必须包含 `evidenceRefs` 和可展示的原文片段。

### 6.7 导入提交预览

```http
POST /api/v1/import-batches/{batchId}/actions/preview-commit
```

```json
{
  "baseCanonVersion": 0,
  "manuscriptChanges": {
    "chaptersToCreate": 18,
    "chaptersToUpdate": 0
  },
  "factChanges": {
    "entities": 24,
    "events": 51,
    "relations": 17,
    "foreshadows": 6
  },
  "warnings": [],
  "blockingIssues": []
}
```

### 6.8 提交导入结果

```http
POST /api/v1/import-batches/{batchId}/actions/commit
Idempotency-Key: 4a56...
```

```json
{
  "expectedCanonVersion": 0,
  "commitMessage": "导入前十八章及确认的故事资料"
}
```

成功返回 `201 Created` 的 `CanonCommit`。

## 7. 故事资料 API

### 7.1 统一实体接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/entities` | 查询实体 |
| `POST` | `/projects/{projectId}/entities` | 手工创建实体草稿 |
| `GET` | `/projects/{projectId}/entities/{entityId}` | 实体详情 |
| `PATCH` | `/projects/{projectId}/entities/{entityId}` | 修改实体草稿或创建变更提案 |
| `GET` | `/projects/{projectId}/entities/{entityId}/history` | 版本历史 |
| `GET` | `/projects/{projectId}/entities/{entityId}/relations` | 相关关系 |
| `GET` | `/projects/{projectId}/entities/{entityId}/events` | 相关事件 |

查询参数示例：

```text
GET /entities?type=CHARACTER&status=CANON&query=顾遥&limit=20
```

### 7.2 人物状态

```text
GET /projects/{projectId}/characters/{characterId}/state
    ?canonVersion=18
    &storyTime=景和十二年九月初三亥时
```

响应包含状态值、有效区间和证据，不只返回最终计算结果。

### 7.3 人物知识边界

```text
GET /projects/{projectId}/characters/{characterId}/knowledge
    ?atChapterId={chapterId}
    &canonVersion=18
```

默认不返回秘密正文全文，只返回当前用户有权查看的摘要和来源。

### 7.4 事件、关系和伏笔

```text
GET /projects/{projectId}/events
GET /projects/{projectId}/relations
GET /projects/{projectId}/foreshadows
GET /projects/{projectId}/timeline
```

MVP 中手工修改正史对象时也先创建 `FactProposal`，再通过统一提交接口进入正史，避免存在绕过版本机制的写路径。

## 8. 故事圣经与大纲 API

### 8.1 故事方向

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/story-directions/latest` | 读取最新候选版本，无候选时返回 `204` |
| `POST` | `/projects/{projectId}/story-directions/actions/generate` | 根据创作意图生成新版本 |
| `POST` | `/projects/{projectId}/story-directions/{setId}/actions/select` | 确认所选方向，要求 `If-Match` |

生成请求可以携带本次调整要求：

```json
{
  "instruction": "减少纯解谜，更突出主角的选择与成长"
}
```

每个版本保存创作意图快照、生成器类型、三个结构化候选、作者问题和选择状态。重新生成创建新版本，不覆盖旧版本。

### 8.2 故事圣经

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/story-bibles/latest` | 读取最新版本，无版本时返回 `204` |
| `POST` | `/projects/{projectId}/story-bibles/actions/generate` | 根据最近一次已确认方向生成草稿 |
| `PUT` | `/projects/{projectId}/story-bibles/{versionId}` | 完整保存草稿内容，要求 `If-Match` |
| `POST` | `/projects/{projectId}/story-bibles/{versionId}/actions/publish` | 发布并设为项目当前版本，要求 `If-Match` |

每个故事圣经版本保存来源方向集、来源候选、生成器、作者调整要求和结构化内容。重新生成创建新草稿；已发布版本不可直接修改。版本历史查询将在分层大纲阶段一并补齐。

### 8.3 大纲

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/outlines/latest` | 读取最新大纲版本，无版本时返回 `204` |
| `GET` | `/projects/{projectId}/outlines` | 按生成编号倒序列出历史版本摘要 |
| `GET` | `/projects/{projectId}/outlines/{outlineId}` | 读取指定历史版本及完整大纲 |
| `POST` | `/projects/{projectId}/outlines/actions/generate` | 根据当前已发布故事圣经生成草稿 |
| `PUT` | `/projects/{projectId}/outlines/{outlineId}` | 完整保存大纲树，要求 `If-Match` |
| `POST` | `/projects/{projectId}/outlines/{outlineId}/actions/publish` | 发布并设为项目当前大纲，要求 `If-Match` |
| `POST` | `/projects/{projectId}/actions/reconstruct-outline` | 从已有正文反推大纲 |

首版大纲以 JSONB 版本快照保存全书、卷/幕和章节三级结构。字数是建议范围：整书默认允许目标字数上下约一万字浮动，卷章不做精确守恒校验。
| `POST` | `/outlines/{outlineId}/actions/analyze-impact` | 分析变更影响 |

生成请求的 `mode=REVISE` 可提供 `baseOutlineVersionId`；不提供时使用最新大纲，提供时必须属于当前项目。`mode=REGENERATE` 不接受基准版本。生成结果直接返回新的草稿大纲版本，保存 `baseOutlineVersionId` 以追溯调整来源，不覆盖当前已发布版本。

当前实现的导入反推入口为：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/projects/{projectId}/imports/{importId}/actions/reverse-plan` | 从已确认导入生成故事圣经和大纲草稿 |

请求指定 `provider`、`mode` 和可选的 `instruction`。`mode=ADAPT_SOURCE` 将导入内容视为可扩写、重构和改变的故事素材，全部章节使用 `PLANNED`；`mode=CONTINUE_MANUSCRIPT` 将原文视为已有正文，使用 `OCCURRED` 表示已发生内容、`PLANNED` 表示未来规划。接口正常调用两次模型，先生成故事圣经，再生成分层大纲；两个产物均保持草稿状态，不更新项目当前发布版本。

### 8.4 章节合同

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/chapters/{chapterId}/contracts` | 合同版本列表 |
| `GET` | `/chapters/{chapterId}/contracts/active` | 当前合同 |
| `POST` | `/chapters/{chapterId}/contracts` | 手工创建合同草稿 |
| `PATCH` | `/chapter-contracts/{contractId}` | 编辑合同草稿 |
| `POST` | `/chapter-contracts/{contractId}/actions/activate` | 激活合同 |
| `POST` | `/chapters/{chapterId}/actions/generate-contract` | AI 生成合同候选 |

激活合同前校验引用的角色、地点、伏笔和大纲节点是否属于同一项目。

## 9. 章节与正文 API

### 9.1 章节目录

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/projects/{projectId}/chapters` | 章节目录 |
| `POST` | `/projects/{projectId}/chapters` | 创建章节 |
| `GET` | `/chapters/{chapterId}` | 章节详情 |
| `PATCH` | `/chapters/{chapterId}` | 修改标题、顺序等元数据 |
| `POST` | `/projects/{projectId}/chapters/actions/reorder` | 批量排序 |

### 9.2 编辑缓冲区

```http
PUT /api/v1/chapters/{chapterId}/editing-buffer
If-Match: "18"
```

```json
{
  "contentFormat": "TIPTAP_JSON",
  "content": {},
  "clientMutationId": "editor-0199..."
}
```

- 自动保存更新编辑缓冲区，不创建正式正文版本。
- 成功返回新 `ETag`。
- 冲突返回 `409 RESOURCE_VERSION_CONFLICT`，并附服务端当前版本摘要。

### 9.3 创建正文版本

```http
POST /api/v1/chapters/{chapterId}/manuscript-versions
Idempotency-Key: e4d2...
```

```json
{
  "source": "EDITING_BUFFER",
  "baseVersionId": "0199...",
  "message": "完成钟楼对质场景"
}
```

正文版本一经创建不可修改。

### 9.4 正文历史与 Diff

```text
GET /chapters/{chapterId}/manuscript-versions
GET /manuscript-versions/{versionId}
GET /manuscript-versions/{fromVersionId}/diff/{toVersionId}
```

Diff 响应同时提供机器结构和适合直接渲染的行级差异，不把 HTML 作为唯一格式。

## 10. 生成与续写 API

### 10.1 生成整章

```http
POST /api/v1/chapters/{chapterId}/actions/generate
Idempotency-Key: 29af...
```

```json
{
  "contractId": "0199...",
  "baseManuscriptVersionId": null,
  "expectedCanonVersion": 18,
  "options": {
    "targetWords": 3200,
    "candidateCount": 1,
    "modelProfile": "BALANCED"
  }
}
```

### 10.2 指定位置续写

```http
POST /api/v1/chapters/{chapterId}/actions/continue
Idempotency-Key: 2f82...
```

```json
{
  "baseManuscriptVersionId": "0199...",
  "selection": {
    "start": 4812,
    "end": 4812,
    "contentHash": "sha256:..."
  },
  "instruction": "继续写到顾遥承认隐瞒密信为止",
  "expectedCanonVersion": 18,
  "options": {
    "targetWords": 1200
  }
}
```

位置偏移必须与 `contentHash` 配合，防止正文修改后把生成结果插入错误位置。

### 10.3 局部改写

```text
POST /chapters/{chapterId}/actions/rewrite
```

请求包含基础正文版本、选区、改写目标和必须保留的信息。生成结果返回独立候选，不直接修改编辑缓冲区。

### 10.4 生成结果

```text
GET /agent-runs/{runId}/artifacts
```

```json
{
  "items": [
    {
      "id": "0199...",
      "type": "MANUSCRIPT_CANDIDATE",
      "content": "……",
      "baseVersionId": "0199...",
      "contextSnapshotId": "0199...",
      "createdAt": "2026-09-25T13:20:00Z"
    }
  ]
}
```

接受候选：

```http
POST /api/v1/chapters/{chapterId}/actions/apply-candidate
Idempotency-Key: 4d19...
```

```json
{
  "artifactId": "0199...",
  "expectedEditingBufferVersion": 18,
  "applyMode": "REPLACE_SELECTION"
}
```

该操作只更新编辑缓冲区，不进入正史。

## 11. 审稿 API

### 11.1 创建审稿任务

```http
POST /api/v1/chapters/{chapterId}/review-runs
Idempotency-Key: f51e...
```

```json
{
  "manuscriptVersionId": "0199...",
  "contractId": "0199...",
  "expectedCanonVersion": 18,
  "dimensions": [
    "CONTRACT_COMPLETION",
    "CONTINUITY",
    "KNOWLEDGE_BOUNDARY",
    "CAUSALITY",
    "STYLE"
  ]
}
```

### 11.2 查询审稿报告

```text
GET /review-reports/{reportId}
GET /review-reports/{reportId}/issues
```

问题结构：

```json
{
  "id": "0199...",
  "severity": "ERROR",
  "type": "KNOWLEDGE_LEAK",
  "message": "林澈提前知道了幕后主使身份",
  "manuscriptRange": {
    "start": 2381,
    "end": 2412
  },
  "evidence": [
    {
      "sourceType": "SECRET",
      "sourceId": "0199...",
      "sourceRef": "canon:secret_07"
    }
  ],
  "suggestedAction": "删除身份判断，仅保留对印记的怀疑",
  "resolution": "OPEN"
}
```

### 11.3 处理审稿问题

```http
PATCH /api/v1/review-issues/{issueId}
If-Match: "1"
```

```json
{
  "resolution": "RESOLVED",
  "note": "已在正文版本 7 中删除越权信息"
}
```

允许的处理状态：`OPEN`、`RESOLVED`、`ACCEPTED_RISK`、`NOT_AN_ISSUE`。`ERROR` 级问题只有项目所有者或编辑者可以标记为接受风险，并必须填写原因。

## 12. 候选事实与正史提交 API

当前已落地的章节审稿使用 `chapter-review/2` 候选结构：六种 `factType` 分别携带类型化 `payload`，并保留主语、谓语、宾语和证据供作者审核。模型输出的 `decision` 会被服务端强制重置为 `PENDING`。本节的独立抽取、预览令牌和批量候选接口仍是后续目标形态。

### 12.1 从正文抽取候选事实

```http
POST /api/v1/chapters/{chapterId}/actions/extract-facts
Idempotency-Key: 1d31...
```

```json
{
  "manuscriptVersionId": "0199...",
  "expectedCanonVersion": 18
}
```

结果通过通用候选接口查询：

```text
GET /projects/{projectId}/fact-proposals?sourceId={versionId}&decision=PENDING
PATCH /fact-proposals/{proposalId}
```

### 12.2 预览提交

```http
POST /api/v1/projects/{projectId}/canon-commits/actions/preview
```

```json
{
  "expectedCanonVersion": 18,
  "chapterId": "0199...",
  "manuscriptVersionId": "0199...",
  "factProposalIds": ["0199...", "0199..."],
  "reviewReportId": "0199..."
}
```

返回正文、事实和图谱变化摘要：

```json
{
  "baseCanonVersion": 18,
  "nextCanonVersion": 19,
  "manuscriptDiff": {
    "chapterId": "0199...",
    "fromVersionId": "0199...",
    "toVersionId": "0199..."
  },
  "factDiff": {
    "eventsAdded": 2,
    "statesChanged": 3,
    "relationsChanged": 1,
    "foreshadowsChanged": 1
  },
  "blockingIssues": [],
  "warnings": [],
  "previewToken": "signed-short-lived-token"
}
```

`previewToken` 绑定请求内容、用户和短期有效时间，防止预览后数据变化却提交旧结果。

### 12.3 执行提交

```http
POST /api/v1/projects/{projectId}/canon-commits
Idempotency-Key: 7f91...
```

```json
{
  "expectedCanonVersion": 18,
  "previewToken": "signed-short-lived-token",
  "message": "提交第十八章：钟楼里的密信"
}
```

```json
{
  "id": "0199...",
  "projectId": "0199...",
  "baseCanonVersion": 18,
  "canonVersion": 19,
  "message": "提交第十八章：钟楼里的密信",
  "projectionStatus": {
    "vector": "PENDING",
    "neo4j": "PENDING"
  },
  "committedAt": "2026-09-25T13:30:00Z"
}
```

### 12.4 提交历史和回滚

```text
GET  /projects/{projectId}/canon-commits
GET  /projects/{projectId}/canon-commits/{commitId}
POST /projects/{projectId}/canon-commits/{commitId}/actions/preview-restore
POST /projects/{projectId}/canon-commits/actions/restore
```

恢复操作创建新的正史版本，不改变或删除历史提交。

## 13. Agent 运行 API

### 13.1 查询与控制

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/agent-runs/{runId}` | 运行详情 |
| `GET` | `/agent-runs/{runId}/steps` | 步骤列表 |
| `GET` | `/agent-runs/{runId}/artifacts` | 产物列表 |
| `GET` | `/agent-runs/{runId}/context` | 上下文证据摘要 |
| `POST` | `/agent-runs/{runId}/actions/cancel` | 请求取消 |
| `POST` | `/agent-runs/{runId}/actions/retry` | 安全重试 |
| `POST` | `/agent-runs/{runId}/actions/resume` | 用户补充信息后继续 |

### 13.2 运行详情

```json
{
  "id": "0199...",
  "runType": "WRITE",
  "status": "RUNNING",
  "target": {
    "type": "CHAPTER",
    "id": "0199..."
  },
  "inputCanonVersion": 18,
  "progress": {
    "currentStep": "DRAFT_MANUSCRIPT",
    "completedSteps": 3,
    "totalSteps": 6,
    "percent": 55
  },
  "usage": {
    "inputTokens": 18642,
    "outputTokens": 2410,
    "estimatedCost": "0.0831",
    "currency": "USD"
  },
  "createdAt": "2026-09-25T13:18:00Z"
}
```

`percent` 只是体验性进度，业务完成状态以步骤状态为准。

## 14. SSE 事件协议

### 14.1 连接

```http
GET /api/v1/agent-runs/{runId}/events
Accept: text/event-stream
Last-Event-ID: 104
```

SSE 适合服务端单向推送任务进度，MVP 不需要为此引入 WebSocket。

### 14.2 事件格式

```text
id: 105
event: step.progress
data: {"runId":"0199...","step":"DRAFT_MANUSCRIPT","message":"正在生成章节草稿","percent":55,"occurredAt":"2026-09-25T13:19:12Z"}
```

事件类型：

| 事件 | 含义 |
| --- | --- |
| `run.started` | 任务开始 |
| `step.started` | 步骤开始 |
| `step.progress` | 步骤进度变化 |
| `artifact.created` | 新候选、报告或大纲产物可用 |
| `run.waiting-for-user` | 需要用户确认或补充信息 |
| `run.succeeded` | 任务成功 |
| `run.failed` | 任务失败 |
| `run.cancelled` | 任务已取消 |
| `heartbeat` | 保持连接 |

### 14.3 重连与补偿

- 每个事件有运行内单调递增的 ID。
- 前端使用 `Last-Event-ID` 重连。
- 服务端至少保留运行期间的事件历史。
- 无法恢复事件流时，前端重新请求运行详情和步骤列表。
- SSE 断开不代表 Agent 任务取消。

## 15. 检索证据与图谱查询 API

### 15.1 上下文证据

```text
GET /agent-runs/{runId}/context
```

返回检索计划、最终使用证据和被过滤原因。普通作者界面可以简化展示，调试界面保留完整数据。

### 15.2 关系图

```text
GET /projects/{projectId}/graph/neighborhood
    ?entityId={entityId}
    &depth=2
    &canonVersion=18
    &relationTypes=TRUSTS,KNOWS_SECRET,PARTICIPATED_IN
```

约束：

- `depth` 默认 1，MVP 最大 3。
- 限制最大节点数和关系数。
- 查询必须包含项目和正史版本。
- Neo4j 投影未达到目标版本时返回投影状态，允许前端选择等待或使用旧版本。

### 15.3 时间线

时间线以 PostgreSQL 正史记录为主，不直接暴露 Cypher：

```text
GET /projects/{projectId}/timeline
    ?characterId={characterId}
    &fromStoryTime={value}
    &toStoryTime={value}
```

## 16. 投影与系统状态 API

普通用户只需要项目级状态：

```text
GET /projects/{projectId}/projection-status
```

```json
{
  "canonVersion": 19,
  "projections": {
    "vector": {
      "lastCompleteCanonVersion": 19,
      "status": "READY"
    },
    "neo4j": {
      "lastCompleteCanonVersion": 18,
      "status": "SYNCING"
    }
  }
}
```

运维接口放在独立权限空间：

```text
POST /api/v1/admin/projects/{projectId}/projections/{type}/actions/rebuild
POST /api/v1/admin/outbox-events/{eventId}/actions/replay
```

## 17. 幂等与并发控制

### 17.1 必须提供幂等键的操作

- 创建项目。
- 上传文件。
- 启动 Agent 任务。
- 创建正文版本。
- 应用生成候选。
- 执行正史提交或恢复。
- 发起投影重建。

服务端保存：

```text
user_id, endpoint, idempotency_key,
request_hash, response_status, response_body, resource_id, expires_at
```

同一个键和同一请求返回第一次结果；同一个键对应不同请求返回 `409 IDEMPOTENCY_CONFLICT`。

### 17.2 乐观锁

- 可变资源更新使用 `If-Match`。
- 缺失 `If-Match` 返回 `428 Precondition Required`。
- 版本不匹配返回 `409 RESOURCE_VERSION_CONFLICT`。
- 正史提交额外校验 `expectedCanonVersion`。
- 不可变版本资源不存在更新接口。

### 17.3 前端自动保存

- 每次保存携带编辑缓冲区版本和 `clientMutationId`。
- 前端同一章节的自动保存串行发送。
- 冲突时保留本地内容，展示合并界面，不自动覆盖服务端。

## 18. 安全要求

- 所有项目资源先校验项目成员身份。
- 上传文件保存前进行类型、大小、文件名和恶意内容检查。
- 下载使用短期授权地址或受保护的流式接口。
- 正文搜索参数化，禁止拼接 SQL 或 Cypher。
- Neo4j 查询只由服务端预定义查询构造，不接受客户端任意 Cypher。
- 模型调用密钥、系统 Prompt 和完整上下文不返回普通前端。
- 日志只记录对象 ID、大小和哈希，不默认记录完整正文。
- 删除、恢复和接受高风险问题需要再次校验权限。

## 19. OpenAPI 与代码生成

Spring Boot 项目维护 OpenAPI 3.1 规范，并把它作为接口契约：

1. 后端 DTO 与 OpenAPI Schema 保持一致。
2. 前端使用 OpenAPI 生成 TypeScript 类型和基础请求客户端。
3. 生成代码放入独立目录，不手工修改。
4. CI 检查破坏性接口变更。
5. 所有枚举、错误码和 SSE 数据结构进入规范。

接口演进规则：

- 新增可选字段属于兼容变更。
- 删除字段、修改类型或改变枚举语义属于破坏性变更。
- 新增枚举值时，前端必须有未知值兜底。
- 破坏性变更进入新的主版本路径。

## 20. 端到端调用示例

### 20.1 从想法生成第一章

```text
POST /projects
POST /projects/{id}/actions/generate-outline
GET  /agent-runs/{runId}/events
GET  /agent-runs/{runId}/artifacts
POST /outlines/{id}/actions/publish
POST /chapters/{id}/actions/generate-contract
POST /chapter-contracts/{id}/actions/activate
POST /chapters/{id}/actions/generate
POST /chapters/{id}/actions/apply-candidate
POST /chapters/{id}/manuscript-versions
POST /chapters/{id}/review-runs
POST /chapters/{id}/actions/extract-facts
PATCH /fact-proposals/{id}
POST /projects/{id}/canon-commits/actions/preview
POST /projects/{id}/canon-commits
```

### 20.2 导入正文并续写

```text
POST /projects
POST /projects/{id}/import-batches
POST /import-batches/{id}/files
POST /import-batches/{id}/actions/parse
GET  /agent-runs/{runId}/events
GET  /import-batches/{id}/parsed-units
GET  /import-batches/{id}/proposals
PATCH /import-batches/{id}/proposals/{proposalId}
POST /import-batches/{id}/actions/preview-commit
POST /import-batches/{id}/actions/commit
POST /projects/{id}/actions/reconstruct-outline
POST /chapters/{nextChapterId}/actions/generate-contract
POST /chapters/{nextChapterId}/actions/continue
```

## 21. MVP 接口实现顺序

### 第一组：基础骨架

- 统一错误模型、认证、请求追踪和幂等组件。
- 项目 CRUD 与创作意图。
- AgentRun 查询、SSE 和取消。

### 第二组：导入闭环

- 导入批次、文件上传和解析任务。
- 解析单元、候选事实和确认。
- 导入提交预览与正史提交。

### 第三组：正文闭环

- 章节目录、编辑缓冲区和正文版本。
- 章节合同、生成、应用候选和 Diff。
- 审稿、事实抽取和章节正史提交。

### 第四组：长期记忆

- 实体、事件、关系、伏笔和时间线查询。
- 上下文证据、向量检索状态和图谱邻域。
- 投影重建和运维接口。

## 22. 后续文档衔接

下一份编写 `NOVEL_AGENT_WORKFLOW_DESIGN.md`，重点确定：

- 每类 AgentRun 的步骤图。
- 步骤输入输出和检查点。
- 自动重试、人工等待、取消与恢复。
- 模型调用和工具权限。
- 正史提交前后的事务边界。
- Outbox 消费及 Neo4j、pgvector 投影更新流程。

---

**API 设计结论**：同步接口负责资源读写和命令受理，耗时 AI 操作统一返回 `AgentRun`，通过 SSE 提供进度。任何生成结果都先成为候选或草稿，只有带正史版本校验、提交预览和幂等键的 `CanonCommit` 接口能够改变项目正史。
