/**
 * 模型接入模块：统一调用端口、供应商执行器、输出协议、请求预算、模型目录、认证与会话。
 *
 * <ul>
 *   <li>接口入口：ChatGptConnectionController。
 *   <li>业务入口：application.StructuredModelGateway 接口、FreeTextModelRequest、GlobalModelCatalogService。
 *   <li>内部实现：RoutedStructuredModelGateway、ModelPromptAssembler、四个执行器及各供应商客户端。
 *   <li>主要依赖：project、prompt、agent、memory；阶段专用规则通过 StagePromptContributor 注入。
 *   <li>领域契约：ModelProvider、CodexSessionPolicy；不依赖规划、导入、写作或正史模块。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.modelaccess;
