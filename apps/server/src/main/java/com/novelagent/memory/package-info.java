/**
 * 记忆模块：为生成请求召回已发布正史与资料，负责混合检索、Token 预算分配与 Embedding。
 *
 * <ul>
 *   <li>接口入口：MemoryController（记忆预览）。
 *   <li>核心服务：NovelMemoryService、MemoryBudgetAllocator、TextEmbeddingService。
 *   <li>主要依赖：agent、planning、project。
 * </ul>
 *
 * <p>模块内分层：{@code api}（控制器）、{@code api.dto}（请求与响应对象）、{@code application}（用例与事务）、
 * {@code domain}（实体与规则）、{@code infrastructure}（存储与外部协议）。
 */
package com.novelagent.memory;
