package com.salkcoding.oswl.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
public class AuthViewController {

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/login/otp-verify")
    public String otpVerifyPage() {
        return "auth/otp-verify";
    }

    @RequestMapping("/error/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden() {
        return "error/403";
    }

    @RequestMapping("/error/401")
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public String unauthorized() {
        return "error/401";
    }

    @RequestMapping("/error/500")
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String serverError() {
        return "error/500";
    }

    @RequestMapping("/error/503")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String serviceUnavailable() {
        return "error/503";
    }
}
