/**
 * 写作模块：章节正文版本、质量检查与润色、写作风格（推荐、试写、试写编辑）、读者体验规划与单章自动编辑循环。
 *
 * <ul>
 *   <li>接口入口：WritingController、QualityReviewController、WritingStyleController、StylePreviewEditingController、DraftLoopController。
 *   <li>核心服务：ManuscriptService、QualityReviewService、WritingGenerationWorkflow（写作类 Agent Graph）。
 *   <li>主要依赖：planning、project、canon、memory、agent、prompt。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.writing;
