package com.salkcoding.oswl.controller.notification;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class WebPushPageController {
    @GetMapping("/my/notifications") @PreAuthorize("isAuthenticated()")
    public String page(){return "notification/preferences";}
}
