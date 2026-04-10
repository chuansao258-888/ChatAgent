package com.yulong.chatagent.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        TraceContext.clear();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void usesHeaderTraceId_whenPresent() throws Exception {
        request.addHeader(TraceContext.TRACE_ID_HEADER, "client-trace");
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo("client-trace");
        verify(chain).doFilter(request, response);
    }

    @Test
    void generatesTraceId_whenHeaderMissing() throws Exception {
        filter.doFilterInternal(request, response, chain);

        String generatedId = response.getHeader(TraceContext.TRACE_ID_HEADER);
        assertThat(generatedId).isNotBlank();
        assertThat(generatedId).hasSize(32); // UUID without dashes
        verify(chain).doFilter(request, response);
    }

    @Test
    void clearsTraceId_afterFilterChain() throws Exception {
        doAnswer(inv -> {
            assertThat(TraceContext.getTraceId()).isNotNull();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void clearsTraceId_evenWhenChainThrows() throws Exception {
        doThrow(new RuntimeException("boom")).when(chain).doFilter(any(), any());

        try {
            filter.doFilterInternal(request, response, chain);
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(TraceContext.getTraceId()).isNull();
    }
}
