# 数据库只读诊断：2026-10-16

## 结论

目前优先优化应用的查询形态与空闲轮询，再清理确认冗余的索引。现有数据量很小，没有分库、分区或大规模调整数据库参数的证据。

本次连接现有配置指定的 PostgreSQL，显式启用只读事务，并设置语句超时 8 秒、锁等待超时 1 秒。仅查询系统目录、统计、聚合和 SELECT 执行计划；没有执行 DDL、数据修改、VACUUM、ANALYZE、配置变更或服务重启。没有读取或记录正文内容、提示词原文及凭据。

## 实测快照

| 项目 | 结果 |
| --- | --- |
| PostgreSQL / pgvector | 16.13 / 0.8.2 |
| Flyway 最新版本 | 064，成功 |
| 数据库总大小 | 31 MB |
| 应用 schema | 58 张普通表，合计约 18 MB，包含索引及 TOAST |
| 模型运行记录 | 188 行，agent_run 总占用约 4.6 MB |
| 正文版本 | 59 行，manuscript_version 总占用约 872 KB |
| 向量分块 | 192 行，均已有 embedding；分块表总占用约 3.3 MB |
| 发布后记忆 | 24 个任务，全部 SUCCEEDED |
| 未发送 Outbox | 0 条 |
| 数据库累计死锁 / 临时文件 | 0 / 0 |
| 观测时其他已开启事务 / 锁等待会话 | 0 / 0 |
| 查询性能统计 | 未安装 pg_stat_statements；慢 SQL 日志阈值为 -1（关闭） |

大小、行数和状态都是观测时快照。pg_stat_user_tables 的行数为估计值；上面的运行记录、正文、向量和任务数量另做了 COUNT 核对。扫描次数为累计统计，不能据此推断当前每秒负载或某条 SQL 的执行耗时。

## 1. 优先：版本列表批量渲染，消除 N+1

`ManuscriptService.manuscriptVersions` 读取全部版本后，逐条调用 `CharacterNameService.render(projectId, title)`。每次 render 都通过 rows 查询人物，再通过 nameForms 查询别名。

因此，除了权限校验，N 个版本至少触发一次版本查询和 2N 次名称查询。`OutlineService.versions` 和历史合同摘要也存在同类调用。

建议在一次用例内加载人物与别名快照，再批量渲染所有标题。快照仅在本次调用中复用，避免引入跨请求缓存后造成改名显示陈旧。保留项目权限及当前名称语义。

## 2. 优先：摘要查询只取需要的字段

`ManuscriptVersionRepository.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc` 返回完整 JPA 实体，随后才映射为摘要 DTO。完整 content、writing_basis、change_summary 等数据随实体被加载，即使页面只需要版本、状态、标题和正文长度。

建议为摘要入口增加专用投影查询：读取 ID、版本、状态、来源、创建时间、`content->>'title'` 和正文长度。不要直接改变该 Repository 方法的返回类型，`FirstThreeChaptersPersistence` 等其他调用确实需要完整正文。大纲历史查询可采用相同思路。

注意：SQL 中从 JSONB 提取标题或计算长度仍可能读取 TOAST 数据，只能减少传输、Java 反序列化和对象分配，不能宣称完全免除大字段 I/O。如以后长度计算成为瓶颈，再评估维护独立长度列，不提前增加冗余字段。

一次只读 SELECT 执行计划样本中，对项目 59 个正文版本提取标题和正文长度，执行时间约 95 ms、shared hit=393；这是一次含 JSONB 提取的辅助诊断查询，并非生产端点原 SQL，也不是稳定延迟基准。需要修改前后用同一查询和代表性数据复测。

## 3. 可合并：5 个排序索引候选

| 候选删除的普通索引 | 应保留的约束 / 唯一索引 | 当前候选大小 |
| --- | --- | --- |
| idx_manuscript_latest | uq_manuscript_version | 16 KB |
| idx_chapter_contract_latest | uq_chapter_contract_version | 8 KB |
| idx_chapter_review_latest | uq_chapter_review_version | 8 KB |
| idx_chapter_contract_review_latest | uq_chapter_contract_review_version | 8 KB |
| canon_memory_history_source | canon_memory_history_commit_id_memory_version_key | 16 KB |

前四组都以 `(project_id, chapter_number, version_number)` 为键，仅版本列排序方向不同。对于项目、章节均为等值条件、版本倒序的现有查询，唯一 B-tree 索引可通过反向扫描满足排序。第五组同理：commit_id 等值后 memory_version 可反向扫描。

源码迁移和在线索引定义均已核对；正文及记忆历史两组还核对了键列、操作符类、排序规则和有效状态。它们是待实施验证的候选，不能因为约束索引 idx_scan 为 0 就删除唯一约束。不同项目/章节排序组合的查询仍需单独检查。

目前合计只有约 56 KB；主要价值是减少长期写入维护，并非立即释放显著空间。若实施，应新增迁移，保留唯一约束，测试查询计划与版本唯一性；在线建删索引的锁和事务要求另外安排，不能改写既有 Flyway 迁移。

依据：[PostgreSQL 16：索引与排序](https://www.postgresql.org/docs/16/indexes-ordering.html)。

## 4. 可优化：空闲任务轮询与 Outbox 事务

- `PublishedMemoryWorker` 默认每 2 秒轮询，即使没有任务也调用 claim；claim 先执行失效任务 UPDATE，再查询 PENDING 任务。canon_memory_job 累计 seq_scan 约 25.9 万，canon_commit 约 16.4 万，但两表当前都很小，顺序扫描本身合理，不能据此认定缺索引。
- 可增加进程内空闲退避，任务提交后唤醒，并保留周期兜底扫描，确保崩溃恢复及漏通知时仍能认领。不要只改成内存事件而丢失持久队列保障。
- `OutboxPublisher.publish` 的事务覆盖最多 20 条事件，每条 Kafka send 最多等待 10 秒。供应商慢或不可用时有长事务风险；当前没有观察到长事务或积压。后续可考虑短事务认领、事务外发送、短事务确认，必须一起设计租约、失败恢复和消费者幂等，保持至少一次投递，不能简单移除事务。

## 5. 先补观测，再做容量优化

没有 pg_stat_statements，当前无法给出真实 SQL 总耗时排行、平均/尾部延迟或最耗时查询的确定结论。建议在独立维护窗口评估启用该扩展，或配置合适的慢查询阈值；其安装可能涉及预加载和重启，本次未实施。慢日志应限制访问，避免记录敏感业务参数。

依据：[PostgreSQL 16：pg_stat_statements](https://www.postgresql.org/docs/16/pgstatstatements.html)、[累计统计说明](https://www.postgresql.org/docs/16/monitoring-stats.html)。

`agent_run` 已经把列表查询与完整提示词/输出读取分开，这点应保留。其 188 行中 user_prompt 存储合计约 2.2 MB、response_text 约 869 KB，是未来主要增长项之一；尚无紧急清理需求。未来可以把大字段归入详情表或建立明确的日志保留/归档策略，保留 Token、费用及审计必要记录，不擅自清除用户历史。

当前只有 192 个向量分块，不建议为此改分区或盲调 HNSW。数据增长后，再对实际项目过滤、版本过滤和 top-k SQL 做执行计划及召回率测试。

## 旧表处理

chapter_contract_version、chapter_contract_review_version、story_direction_set 当前均为 0 行，但仍有历史读取、外键、资料快照或映射等兼容关系。清空表与删除表的收益很小，不能仅因当前无数据就删除。story_bible_version 当前有 1 行，仍是活动规划资料来源。

## 建议实施顺序

1. 同次查询复用姓名/别名快照，消除版本列表 N+1。
2. 给摘要入口增加投影查询，避免返回完整正文实体。
3. 补充真实慢 SQL 观测，再实施经回归验证的冗余索引清理。
4. 根据负载处理空闲退避与 Outbox 短事务，最后考虑日志归档及大规模向量优化。
