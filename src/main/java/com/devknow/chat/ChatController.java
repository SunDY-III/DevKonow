package com.devknow.chat;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式对话入口。
 * 为什么选 SSE 而不是 WebSocket（面试点）：流式回答是典型的服务端单向推送，
 * SSE 基于普通 HTTP、浏览器原生自动重连、无需协议升级握手；需要双向实时交互才值得上 WS。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String conversationId, @RequestParam String question) {
        // 超时 150 秒：须大于 LLM stream 超时（120s）+ 网络缓冲，避免 LLM 断连后 SSE 空挂
        SseEmitter emitter = new SseEmitter(150_000L);
        Long userId = UserContext.require();   // ThreadLocal 在异步线程不可见，这里先取值再传递
        chatService.streamChat(userId, userId + ":" + conversationId, question, emitter);
        return emitter;
    }
}
