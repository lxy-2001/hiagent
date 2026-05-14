package com.agentflow.web.chat;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final Executor applicationTaskExecutor;

    public ChatController(ChatService chatService, Executor applicationTaskExecutor) {
        this.chatService = chatService;
        this.applicationTaskExecutor = applicationTaskExecutor;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/stream")
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        applicationTaskExecutor.execute(() -> streamInBackground(request, emitter));
        return emitter;
    }

    private void streamInBackground(ChatRequest request, SseEmitter emitter) {
        try {
            ChatResponse response = chatService.stream(request, delta -> sendDelta(emitter, delta));
            emitter.send(SseEmitter.event().name("done").data(response));
            emitter.complete();
        } catch (Exception ex) {
            sendError(emitter, ex);
        }
    }

    private void sendDelta(SseEmitter emitter, String delta) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("content", delta)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void sendError(SseEmitter emitter, Exception ex) {
        try {
            String message = ex.getMessage() == null ? "Chat stream failed" : ex.getMessage();
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }
}
