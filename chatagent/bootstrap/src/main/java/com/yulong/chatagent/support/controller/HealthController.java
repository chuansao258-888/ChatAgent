package com.yulong.chatagent.support.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight endpoints used for health checks and manual SSE smoke testing.
 */
@RestController
public class HealthController {

    /**
     * Returns a simple liveness response.
     *
     * @return fixed health string
     */
    @RequestMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * Convenience endpoint used when manually checking that the application is reachable.
     *
     * @return fixed test string
     */
    @GetMapping("/sse-test")
    public String sseTest() {
        return "ok";
    }
}
