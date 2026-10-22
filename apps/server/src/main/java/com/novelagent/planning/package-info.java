/**
 * 规划模块：雪花规划、确认资料快照、分层大纲、规划分块与检查点。
 *
 * <ul>
 *   <li>接口入口：OutlineController、SnowflakePlanningController、PlanningBatchController、PlanningCheckpointController。
 *   <li>核心服务：OutlineService、OutlineGenerationWorkflow、ConfirmedSnowflakeService；通过 modelaccess 的调用端口生成内容。
 *   <li>OutlinePromptContributor 提供规划规则，模型接入模块不引用规划实现。
 *   <li>主要依赖：project、agent（运行记录）、memory、modelaccess、prompt。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.planning;
