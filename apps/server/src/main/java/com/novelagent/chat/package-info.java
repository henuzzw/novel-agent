/**
 * 项目对话模块：按需读取项目资料与记忆，生成人物档案、雪花底稿和大纲的修改候选，由作者确认后保存。
 *
 * <ul>
 *   <li>接口入口：ProjectChatController。
 *   <li>核心服务：ProjectChatService、ProjectChatStore。
 *   <li>主要依赖：planning、project、memory、prompt。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.chat;
