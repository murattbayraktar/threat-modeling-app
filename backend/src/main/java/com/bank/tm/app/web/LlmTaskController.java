package com.bank.tm.app.web;

import com.bank.tm.core.service.ReviewService;
import com.bank.tm.core.service.Views;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Tarayıcı LLM modu: backend istemi ve şemayı hazırlar, tarayıcı (WebLLM) çalıştırıp ham çıktıyı geri gönderir.
 * Doğrulama ve akışın devamı yine backend'dedir.
 */
@RestController
@RequestMapping("/api/llm-tasks")
public class LlmTaskController {

    public record Result(String output, String model, Long durationMs) {
    }

    private final ReviewService reviews;

    public LlmTaskController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public List<Views.LlmTaskView> pending(@RequestParam(value = "reviewId", required = false) String reviewId) {
        return reviews.pendingLlmTasks(reviewId);
    }

    @PostMapping("/{callId}/result")
    public Map<String, String> submit(@PathVariable("callId") String callId, @RequestBody Result body,
                                      @RequestHeader(value = "X-User", required = false) String user) {
        reviews.submitLlmResult(callId, body.output(), body.model(), body.durationMs(), CurrentUser.of(user));
        return Map.of("status", "ok");
    }
}
