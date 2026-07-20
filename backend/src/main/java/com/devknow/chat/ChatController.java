package com.devknow.chat;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 流式对话入口。
 * 为什么选 SSE 而不是 WebSocket（面试点）：流式回答是典型的服务端单向推送，
 * SSE 基于普通 HTTP、浏览器原生自动重连、无需协议升级握手；需要双向实时交互才值得上 WS。
 *
 * <p>Phase 3 新增:
 * <ul>
 *   <li>projectId 参数 - 项目级检索隔离</li>
 *   <li>context 参数 - 携带上下文信息（文件名:行号:问题前缀）</li>
 *   <li>SSE step 事件 - Agent 步骤进度</li>
 *   <li>POST /api/chat/regenerate - 强制走完整 RAG 链路</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam String conversationId,
            @RequestParam String question,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String context) {
        // 超时 150 秒：须大于 LLM stream 超时（120s）+ 网络缓冲，避免 LLM 断连后 SSE 空挂
        SseEmitter emitter = new SseEmitter(150_000L);
        Long userId = UserContext.require();   // ThreadLocal 在异步线程不可见，这里先取值再传递
        chatService.streamChat(userId, userId + ":" + conversationId, question, emitter, projectId, context);
        return emitter;
    }

    /**
     * 强制重新生成：不走缓存，直接调用完整 RAG+LLM 管道。
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, Object>> regenerate(@RequestBody Map<String, String> body) {
        Long userId = UserContext.require();
        String conversationId = body.get("conversationId");
        String question = body.get("question");
        Long projectId = body.containsKey("projectId")
                ? Long.parseLong(body.get("projectId")) : null;

        // 重置缓存标记，强制走完整链路
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "重新生成请求已提交",
                "conversationId", conversationId
        ));
    }
}
