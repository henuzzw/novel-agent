# HTTP 请求日志

## 记录范围

拦截器记录 /api/** 的路径参数、查询／表单参数及白名单请求头（Content-Type、Accept、If-Match、If-None-Match、Idempotency-Key）。JSON 入参由 RequestBodyAdvice 在原转换器读完后记录，响应由 ResponseBodyAdvice 提供摘要，不额外消费请求流或改变响应内容。

过滤器在 MVC 外生成 UUID 请求标识，响应头为 X-Request-Id；记录 HTTP_START 和 HTTP_END。慢请求的 START / PARAMETERS / INPUT 可立即定位，END 等结束后记录状态和耗时。与既有 Agent runId 独立，不拿请求 ID 当幂等键。MDC 关联同一请求线程的业务／模型日志，退出时恢复；后台任务和模型子线程不会自动继承。

projectId 来自项目路径，创建项目成功后从响应补齐新项目 ID。项目列表、全局配置等无单一所属项目的接口记“-”，不查询数据库猜测。历史请求不回填。

## 脱敏与限长

- 不记录 Authorization、Cookie 或任意请求头，只记录白名单。
- 密钥、密码、令牌、凭据／秘密字段递归脱敏；Bearer、常见 API key 及 URL 内嵌凭据再做字符串脱敏。
- 已知正文、原文 text/content/body/quote、样本、作者指令、Prompt、responseText、错误详情、摘录字段只记录字符数。content 为对象时保留结构，递归处理；完整私有模型请求／响应继续在原有权限受控任务详情查看。
- 其他字符串超过 512 字符只记长度；数组最多 20 项，最大深度 8、节点 300；每份最终摘要最多 4000 字符并标注截断。日志不是完整响应存档。
- 不修改输入或输出对象。防控制字符注入，摘要失败仅记 UNAVAILABLE，不影响业务响应。
- 字段脱敏不等于覆盖所有未知名称的个人信息，部署时仍需限制日志访问、保留期与下载权限；新增敏感字段应同步规则及测试。

## 特殊请求

上传仅记录文件名、类型和字节数，不读文件内容；下载仅记录二进制大小。SSE 不缓存响应、不打印事件正文，HTTP_ASYNC_OPEN 表示连接建立，HTTP_END 在异步完成时写一次；时长为连接时长而非生成时长。

跨域等 MVC 前拒绝、无效 JSON 或未读取的入参记录 NOT_READ / NO_BODY 及状态，不打印原始非法报文。过滤器前的容器拒绝、反向代理请求、非 /api/** 请求不在范围内。

## 使用

默认 HTTP_LOG_LEVEL=INFO，可在 .env.local 设为 OFF 关闭这组日志。沿用 LOG_FILE，默认 apps/server/logs/novel-agent-server.log，沿用每日／大小滚动与现有保留策略；不新增数据库迁移。

按 projectId 定位项目请求，按响应头 X-Request-Id 定位单次请求：

~~~text
HTTP_START requestId=... projectId=... method=POST path=.../actions/reverse-plan
HTTP_PARAMETERS requestId=... projectId=... parameters=...
HTTP_INPUT requestId=... projectId=... body={"provider":"LOCAL_CODEX","mode":"ADAPT_SOURCE","analysisVersion":4,...}
HTTP_END requestId=... projectId=... status=200 durationMs=... input=... output=...
~~~

需重启后端加载。此改动仅补可观测性，未改变“原文解析 → 作者确认 → 圣经 → 大纲”的导入路径，也未新增故事方向生成。

## 验证

专项测试覆盖递归脱敏／限长／不修改对象、关联项目与请求头、同线程 MDC 恢复、JSON 入出参、错误响应和无效 JSON、上传／下载、流式响应、MVC 前 403、未处理异常及非 API 跳过。使用本地接口替身，无真实项目修改或付费模型请求。

本轮新增 11 项测试；后端全量 533 项，488 通过、45 项外部环境门禁跳过，0 失败。没有重启正式后端，需重启后生效；未做真实数据库或生产流量回放。
