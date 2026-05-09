package com.salkcoding.oswl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/glossary")
public class GlossaryController {

    @GetMapping
    public String glossary() {
        return "glossary/index";
    }
}
