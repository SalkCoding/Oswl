package com.salkcoding.oswl.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class AuthViewController {

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @RequestMapping("/error/403")
    public String forbidden() {
        return "error/403";
    }

    @RequestMapping("/error/401")
    public String unauthorized() {
        return "error/401";
    }

    @RequestMapping("/error/500")
    public String serverError() {
        return "error/500";
    }

    @RequestMapping("/error/503")
    public String serviceUnavailable() {
        return "error/503";
    }
}
