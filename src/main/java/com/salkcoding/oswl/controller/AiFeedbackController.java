package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.controller.spec.AiFeedbackControllerSpec;
import com.salkcoding.oswl.domain.entity.AiFeedback;
import com.salkcoding.oswl.dto.api.AiFeedbackRequest;
import com.salkcoding.oswl.repository.AiFeedbackRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/feedback")
@RequiredArgsConstructor
public class AiFeedbackController implements AiFeedbackControllerSpec {

    private final AiFeedbackRepository feedbackRepository;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> submit(@Valid @RequestBody AiFeedbackRequest request,
                                       @AuthenticationPrincipal OswlUserPrincipal principal) {
        Long userId = principal != null ? principal.getUserId() : null;
        feedbackRepository.save(AiFeedback.builder()
                .userId(userId)
                .targetType(request.getTargetType().strip())
                .targetKey(request.getTargetKey().strip())
                .helpful(Boolean.TRUE.equals(request.getHelpful()))
                .comment(request.getComment())
                .build());
        return ResponseEntity.noContent().build();
    }
}
