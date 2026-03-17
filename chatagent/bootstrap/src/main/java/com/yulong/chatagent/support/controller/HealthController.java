package com.yulong.chatagent.support.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @RequestMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/sse-test")
    public String sseTest() {
        return "ok";
    }
}
