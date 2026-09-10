package com.salkcoding.oswl.controller.notification;
import com.salkcoding.oswl.controller.spec.WebPushControllerSpec;
import com.salkcoding.oswl.dto.api.*;
import com.salkcoding.oswl.service.notification.WebPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/my/web-push")
public class WebPushController implements WebPushControllerSpec {
    private final WebPushService service;
    @GetMapping @PreAuthorize("isAuthenticated()")
    public WebPushStatus status(){return service.status();}
    @PostMapping @PreAuthorize("isAuthenticated()")
    public Long subscribe(@RequestBody WebPushSubscriptionRequest request){return service.subscribe(request);}
    @DeleteMapping("/{id}") @PreAuthorize("isAuthenticated()")
    public void unsubscribe(@PathVariable Long id){service.unsubscribe(id);}
}
