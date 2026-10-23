/**
 * 提示词模块：各阶段提示词的目录与文件加载。提示词只从 {@code src/main/resources/prompts/*.txt} 读取，
 * 不提供在线编辑；需要调整时直接修改文件并重新部署。
 *
 * <ul>
 *   <li>接口入口：无（不对外暴露 HTTP 接口）。
 *   <li>核心服务：AgentPromptService、AgentPromptCatalog、AgentPromptDefaults。
 *   <li>主要依赖：planning、platform。
 * </ul>
 *
 * <p>模块内仅有 {@code application} 层：AgentPromptDefaults 读取文件，AgentPromptCatalog 定义阶段目录，
 * AgentPromptService 为模型调用解析会话角色与基于文件内容的版本标识。
 */
package com.novelagent.prompt;
