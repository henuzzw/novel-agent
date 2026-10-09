# ChatGPT OAuth 直连接入

## 方案与边界

参考 OpenClaw 的账号授权、流式请求、显式账号切换与错误恢复原则，采用官方 Sign in with ChatGPT（SIWC）应用专属授权及公开 Responses API；不是复制 OpenClaw 的原生 Codex 客户端身份或后台接口。

- 原有代码已提交、推送并合并到 `master`：业务提交 `0e9d2db`，合并提交 `04481d2`。
- 本轮在 `codex/openclaw-oauth-direct` 开发；默认入口仍为 Codex App Server，登录成功不会自动切换。
- SIWC 是 Beta，账号、地区、组织策略和模型访问是否支持，必须通过实际授权与请求确认。没有自动改用 API Key 计费或切换 DeepSeek。
- 纯文本输出不变；HTTP 请求本身使用 JSON 传输，但不要求模型生成 JSON，不发送 JSON Schema。
- App Server 仍可手动选择；其已挂载会话提前返回导致指令未更新的问题，不宣称已经修复。新直连路线每轮明确发送当前指令。

依据：[官方登录协议](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、[模型及推理协议](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[账号与会话](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)、[OpenClaw OpenAI 认证说明](https://docs.openclaw.ai/providers/openai/authentication)。

## 使用流程

1. 服务端配置随机的 `CHATGPT_CONNECTION_ADMIN_KEY`，重启加载 V056。口令不是 OpenAI API Key，也不是 ChatGPT 密码，不得设置成前端环境变量或加入版本库。
2. 打开全局模型设置，在“ChatGPT 接入”输入连接管理口令并解锁；口令仅存在当前组件内存，关闭面板后清除。
3. 点击 Continue with ChatGPT，在官方页面授权本应用使用模型额度。服务端启动仅监听 `127.0.0.1` 的临时回调端口。
4. 本地浏览器自动回调。手机或远程浏览器无法连接服务端环回地址时，将浏览器最终回调地址完整粘贴到面板；地址含短期授权码，应按凭据保护，不发到聊天或日志。
5. 页面显示账号已连接且获得模型用量授权后，显式选择“ChatGPT OAuth 直连（Beta）”；再读取实际模型目录，选择模型与推理强度并保存。
6. 后续任务沿用当前模板与模型设置。任务详情保留实际输入历史、当前系统指令、流式公开输出与供应商返回用量。
7. 断开账号会清除本地令牌并尝试远端撤销。网络失败时明确显示“远端撤销未确认”，需到 ChatGPT 设置中断开应用；不误报已撤销。应用注册与身份绑定保留，重新登录使用原账号。

登录、切换、断开前检查当前用户本次服务启动后的运行任务及有效直连租约，拒绝直接切换。历史遗留 RUNNING 不改状态、也不永久阻塞新入口；有效直连租约跨实例仍阻塞。原 App Server 不支持多实例统一活跃探测，本地部署按单后端使用。当前正在生成的请求已经冻结入口与模型，失败不会改用另一个入口。

## 认证与安全

- 初次使用 `dynamic_agent_client` 动态注册 Novel Agent，持久化应用标识及每主机稳定 UUID，后续使用已签发的应用标识。
- 每次登录独立 PKCE S256、state、nonce，回调地址必须与发起时完全一致；重复参数、重复回调、过期和取消的尝试拒绝。
- ID Token 用成熟 JWT 库验证 JWKS 签名、issuer、audience、有效期、nonce 与 subject；重新授权或刷新不得替换绑定身份。
- 身份连接与模型用量授权分别展示，没有 `chatgpt.tokens.use.direct` 权限不允许推理。
- `~/.novel-agent/chatgpt`（可用 `CHATGPT_CREDENTIAL_DIRECTORY` 改到其他私有绝对路径）保存令牌，目录/文件仅所有者可读写，原子替换；同用户跨 JVM 文件锁保护轮换刷新。不是明文写数据库，也不声称使用了加密存储。
- 不导入桌面 Codex 的 `auth.json`，不向前端返回 access/refresh token，不将 OAuth 地址、授权码、state、nonce 或管理口令写普通 HTTP 日志。
- `X-ChatGPT-Admin-Key` 保护新增 `/settings/model/chatgpt` 账号管理接口，未配置返回 503，错误口令返回 403。请求通过 HTTPS 或可信本地连接传输，不能在公网明文 HTTP 中发送口令/回调码。
- 该口令不替代全站认证。当前业务接口仍使用固定开发用户，其他生成接口没有新增账号级认证；已经连接的额度也可能被公开业务接口调用。禁止无保护地暴露公网，需要网络访问控制或正式应用用户认证后再公开部署。
- 失败信息只展示安全的供应商错误码、HTTP 状态和请求编号，不回显任意供应商文本或小说资料。目录权限不安全则拒绝读写。

## 同会话与每轮指令

```text
当前入口与全局模型设置冻结
→ 登录凭据与用量权限检查
→ 认领项目/工作流历史租约
→ 当前 instructions + 历史 input + 本轮用户输入
→ Responses HTTP SSE
→ 公开文字预览（推理内容不公开）
→ response.completed
→ 现有纯文本解析与业务处理
→ 保存完整成功历史、释放租约
```

纯 HTTP 路线不使用供应商 `conversation` 或 `previous_response_id`，同会话由本系统保存并发送完整输入/输出。保留供应商返回的加密推理项以便后续传回，不解密或展示推理。相关历史和任务 Prompt 均按私有手稿保护。

原文解析、书名、雪花步骤、人物设计、方向、圣经与大纲共享项目规划历史；正文检查/裁决仍按既有 NEW_THREAD 策略独立，不因迁移混入规划会话。模板配置版本变化或账号绑定变化会轮换历史；正常规划阶段切换不轮换，每轮发送对应最新系统角色。

完整历史纳入上下文估算，不自动删除、摘要或压缩，也不额外调用压缩 Agent。超过配置容量时明确拒绝，不假定 Codex CLI 自动压缩能力适用于此路线。当前尚无独立“重置直连历史”按钮，模板保存导致版本变化时会按既有策略轮换。

租约等待不占长事务；30 分钟租约随实际公开文字、推理增量或增长用量续期。正常停止/失败释放租约；进程崩溃后可能需等待租约到期，不自动重发失败请求。

## 流式与错误

- 每轮 `store=false`、`stream=true`，模型、推理强度与输入按当前有效配置发送。不发送 `max_output_tokens` 等 SIWC 不支持参数，界面输出上限仍仅参考预算。
- 空闲超时沿用 `CODEX_TURN_TIMEOUT_SECONDS`；有效输出或推理进展续期，心跳与空增量不续期，无固定总生成时长。
- 只有 completed 状态和可用完整文字成功，failed、incomplete、断流、缺少完成事件、拒答、停止均不能发布结果；部分文字只作任务预览。
- 正史、人物档案、关系与伏笔仍按现有业务流程更新，不靠 HTTP 会话自动写入。
- 直连模型目录来自账号实际 `/models` 结果；目录未承诺推理强度能力，页面展示协议选项不代表该模型支持全部强度，实际拒绝会明确失败。

## 表与接口

V056 新增：

| 表 | 用途 |
| --- | --- |
| `user_chatgpt_transport` | 当前用户的显式入口选择、乐观版本 |
| `model_http_conversation` | 项目/工作流历史、账号绑定指纹、模板版本和生成租约，无凭据 |

`/api/v1/settings/model/chatgpt` 下 GET 读取状态，PUT `/transport` 选择入口，POST `/login` 发起登录，POST `/callback` 完成远程回调，POST `/login/cancel` 取消，POST `/logout` 断开。

模块职责：`api` 管理接口与管理口令护栏；`application` 登录、切换、历史预算与调度；`infrastructure` 私有凭据文件、HTTP/JWT、SSE 和 PostgreSQL 历史。原公共 `StructuredModelGateway` 仍负责模板冻结、纯文本协议与任务记录，DeepSeek 不变。

## 验证范围

仅运行本轮相关小范围验证，限制 Maven CPU/堆内存，不跑全量、不调用真实模型：

- 本机 HTTP 服务捕获连续两轮实际请求，验证 instructions 改变、完整历史、Bearer、纯文本无 Schema、禁用存储及流式字段。
- 真实 JWT 签名验证、state/nonce/回调重放、取消、并发刷新与轮换、失效授权、失败撤销及日志脱敏。
- 独立 PostgreSQL schema 应用 V056，验证租约、权限、版本轮换、入口切换和公共网关两轮 HTTP + 历史落库；业务解析失败不追加历史。清理只删除随机测试 schema。
- 前端针对性单元、类型/ESLint、单尺寸浏览器交互与截图。不增加多尺寸长流程。

真实 SIWC 账号授权、实际模型权限、供应商端停止行为、代理断线恢复和文学效果尚未验证；不得将模拟成功宣称为生产可用或收费承诺。

本轮结果：后端相关24项（含隔离数据库3项）、前端6项、单尺寸浏览器1项及类型/ESLint通过。作者授权后已配置私有管理口令并重启8081，实际 V056 迁移和健康检查成功，前端5173代理可读取受保护连接状态；本地登录发起/取消可用，未进行真实账号授权或模型推理。默认仍为 App Server，原模型及强度、设置版本保持；未恢复失败任务或修改小说内容。
