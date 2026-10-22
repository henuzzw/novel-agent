/**
 * 自动化与运行记录模块：章节推进自动化任务、模型运行记录与查询、Token 统计、生成停止；以及供模型读取资料的只读工具。
 *
 * <ul>
 *   <li>接口入口：AutomationController、AgentRunController、AgentRunOutputController、GenerationControlController。
 *   <li>核心服务：AutomationService、AgentRunRecorder（每次模型调用的请求快照与结果记录）、AgentRunQueryService。
 *   <li>主要依赖：writing、planning、memory、project、canon。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.agent;
