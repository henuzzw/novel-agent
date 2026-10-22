/**
 * 规划模块：雪花规划、故事圣经、分层大纲、规划分块与检查点；同时包含所有模型共用的结构化调用网关与执行器。
 *
 * <ul>
 *   <li>接口入口：OutlineController、SnowflakePlanningController、PlanningBatchController、PlanningCheckpointController。
 *   <li>核心服务：OutlineService、OutlineGenerationWorkflow；infrastructure 下的 StructuredModelGateway 与 *Executor 是所有模型调用的统一入口。
 *   <li>主要依赖：project、agent（运行记录）、memory、modelaccess、prompt。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.planning;
