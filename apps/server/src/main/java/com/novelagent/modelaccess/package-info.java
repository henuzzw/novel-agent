/**
 * 模型接入模块：ChatGPT OAuth 直连、HTTP 会话历史，以及 Codex 项目会话与资料增量。
 *
 * <ul>
 *   <li>接口入口：ChatGptConnectionController。
 *   <li>核心服务：ChatGptDirectGateway、CodexProjectGateway、ChatGptOAuthService。
 *   <li>主要依赖：project、planning、agent、memory。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.modelaccess;
