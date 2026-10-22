/**
 * 导入模块：导入已有作品、原文分段解析（人物、世界观、事件等），以及基于原作反推圣经与大纲。
 *
 * <ul>
 *   <li>接口入口：WorkImportController、ImportAnalysisController、ImportedStoryBlueprintController。
 *   <li>核心服务：WorkImportService、ImportAnalysisRunner、ImportedStoryBlueprintService。
 *   <li>主要依赖：planning、project、agent、prompt。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.ingest;
