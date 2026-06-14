package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ImportWebhookControllerSpec;
import com.salkcoding.oswl.service.ImportWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Inbound VCS push webhooks — no session auth; verified per-project secret.
 */
@RestController
@RequiredArgsConstructor
public class ImportWebhookController implements ImportWebhookControllerSpec {

    private final ImportWebhookService importWebhookService;

    @PostMapping("/api/import/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        Map<String, String> headers = extractHeaders(request);
        ImportWebhookService.WebhookResult result =
                importWebhookService.handlePush(body != null ? body : "", headers);

        Map<String, Object> payload = new HashMap<>();
        payload.put("accepted", result.accepted());
        payload.put("message", result.message());
        if (result.jobId() != null) {
            payload.put("jobId", result.jobId());
        }
        return result.accepted()
                ? ResponseEntity.ok(payload)
                : ResponseEntity.badRequest().body(payload);
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        if (request == null) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        var names = request.getHeaderNames();
        if (names == null) {
            return map;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            map.put(name, request.getHeader(name));
        }
        return map;
    }
}
