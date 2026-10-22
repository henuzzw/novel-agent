/**
 * 平台模块：与业务无关的横切能力，供所有模块复用。
 *
 * <ul>
 *   <li>接口入口：无业务接口；api 包提供 ApiResponses（ETag、204/404 等响应工具）与 ApiExceptionHandler（统一异常映射）。
 *   <li>核心服务：support 包：Json、Sha256、Text、GraphStateCodec；web 包：HTTP 请求日志；config 包：Web 配置。
 *   <li>主要依赖：原则上不依赖业务模块；ApiExceptionHandler 引用各模块的业务异常以映射 HTTP 状态。
 * </ul>
 */
package com.novelagent.platform;
