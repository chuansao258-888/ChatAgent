package com.yulong.chatagent.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
/**
 * Ensures every HTTP request has a trace identifier and propagates it to both
 * response headers and the logging context.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            // Generate a compact trace ID when the caller did not provide one.
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        TraceContext.setTraceId(traceId);
        // Echo the trace ID back so clients can correlate responses with logs.
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
