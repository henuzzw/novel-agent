/**
 * 项目模块：小说项目、创作意图、创作策略与创作边界、全局模型设置，以及统一的项目归属校验。
 *
 * <ul>
 *   <li>接口入口：ProjectController、CreativeStrategyController、CreativeBoundaryController、GlobalModelSettingsController。
 *   <li>核心服务：ProjectAccessService（所有模块读写项目数据前的归属校验）、ProjectService、CreativeStrategyService。
 *   <li>主要依赖：planning、ingest、modelaccess。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.project;
