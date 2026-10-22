/**
 * 正史模块：发布正文、正史提交与替换、类型化事实投影（事件、知识、关系、状态、伏笔）、人物档案与发布后记忆整理。
 *
 * <ul>
 *   <li>接口入口：CanonPublicationController、CanonCommitController、TypedCanonController、CharacterProfileController。
 *   <li>核心服务：CanonPublicationService、CanonCommitService、PublishedMemoryService、TypedCanonQueryService。
 *   <li>主要依赖：writing（正文与审稿）、project、planning、agent。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.canon;
