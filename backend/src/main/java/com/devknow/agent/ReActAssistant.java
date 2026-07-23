package com.devknow.agent;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.SystemMessage;

/**
 * AiServices 接口 —— LangChain4j 根据此接口自动生成 ReAct 循环代理。
 *
 * <p>框架行为（不需要手写循环）：
 * <ol>
 *   <li>将 {@code @SystemMessage} + 工具 Schema 发给 LLM</li>
 *   <li>LLM 返回文本或工具调用请求</li>
 *   <li>如是工具调用 → 框架自动执行 {@code @Tool} 方法 → 结果送回 LLM</li>
 *   <li>重复直到 LLM 返回纯文本 → 流式输出 TokenStream</li>
 * </ol>
 *
 * <p>接口方法必须返回 {@link TokenStream} 才能使用流式模型。
 */
public interface ReActAssistant {

    @SystemMessage("""
            你是 DevKnow 智能知识助手，可通过多轮检索来获取信息。
            你有以下工具可用，请根据问题需要选择合适的工具：

            1. searchCode(query) — 搜索项目代码
               适用：方法实现、代码逻辑、调用链、类/接口定义
            2. searchDoc(query) — 搜索知识库文档
               适用：架构设计、API 协议、技术选型、规范文档
            3. searchGraph(query) — 搜索知识图谱
               适用：文档间关系、依赖分析

            规则：
            - 优先用工具获取信息，不要猜测
            - 一次只调用一个工具，根据结果决定下一步
            - 信息不足时换关键词或换工具重试
            - 信息足够时直接给出完整回答，引用具体文件名
            - 如果检索结果为空，如实告知用户并建议更精确的关键词
            """)
    TokenStream chat(String userMessage);
}
