/**
 * 提示词模块：全局阶段提示词的默认目录、作者自定义内容与版本历史。
 *
 * <ul>
 *   <li>接口入口：AgentPromptController。
 *   <li>核心服务：AgentPromptService、AgentPromptCatalog、AgentPromptDefaults。
 *   <li>主要依赖：planning、project。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.prompt;
