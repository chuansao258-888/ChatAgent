package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WebSearchRequestTest {

    private static final int HARD_MAX = 8;
    private static final int MAX_QUERY_CHARS = 300;
    private static final int DEFAULT_MAX = 5;

    @Nested
    class BasicValidation {
        @Test
        void shouldValidateMinimalRequest() {
            WebSearchRequest req = WebSearchRequest.validate("test query", null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);

            assertThat(req.query()).isEqualTo("test query");
            assertThat(req.maxResults()).isEqualTo(DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.ANY);
            assertThat(req.domains()).isEmpty();
            assertThat(req.requestedAt()).isNotNull();
        }

        @Test
        void shouldTrimQuery() {
            WebSearchRequest req = WebSearchRequest.validate("  hello world  ", null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.query()).isEqualTo("hello world");
        }
    }

    @Nested
    class QueryValidation {
        @Test
        void shouldRejectNullQuery() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WebSearchRequest.validate(null, null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX))
                    .withMessageContaining("query must not be empty");
        }

        @Test
        void shouldRejectBlankQuery() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WebSearchRequest.validate("   ", null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX))
                    .withMessageContaining("query must not be empty");
        }

        @Test
        void shouldRejectOversizedQuery() {
            String longQuery = "x".repeat(301);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WebSearchRequest.validate(longQuery, null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX))
                    .withMessageContaining("exceeds maximum length");
        }

        @Test
        void shouldAcceptMaxLengthQuery() {
            String maxQuery = "x".repeat(300);
            WebSearchRequest req = WebSearchRequest.validate(maxQuery, null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.query()).hasSize(300);
        }
    }

    @Nested
    class MaxResultsClamping {
        @Test
        void shouldClampToUpperBound() {
            WebSearchRequest req = WebSearchRequest.validate("test", 100, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.maxResults()).isEqualTo(HARD_MAX);
        }

        @Test
        void shouldClampToLowerBound() {
            WebSearchRequest req = WebSearchRequest.validate("test", 0, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.maxResults()).isEqualTo(1);
        }

        @Test
        void shouldClampNegative() {
            WebSearchRequest req = WebSearchRequest.validate("test", -5, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.maxResults()).isEqualTo(1);
        }

        @Test
        void shouldUseConfiguredDefault() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, HARD_MAX, MAX_QUERY_CHARS, 6);
            assertThat(req.maxResults()).isEqualTo(6);
        }

        @Test
        void shouldClampConfiguredDefaultWhenAboveHardMax() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, 3, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.maxResults()).isEqualTo(3);
        }

        @Test
        void shouldUseDefaultOf3() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, HARD_MAX, MAX_QUERY_CHARS, 3);
            assertThat(req.maxResults()).isEqualTo(3);
        }

        @Test
        void shouldClampConfiguredDefaultZeroToOne() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, HARD_MAX, MAX_QUERY_CHARS, 0);
            assertThat(req.maxResults()).isEqualTo(1);
        }

        @Test
        void shouldClampConfiguredDefaultNegativeToOne() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, HARD_MAX, MAX_QUERY_CHARS, -5);
            assertThat(req.maxResults()).isEqualTo(1);
        }

        @Test
        void shouldAcceptValidValue() {
            WebSearchRequest req = WebSearchRequest.validate("test", 6, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.maxResults()).isEqualTo(6);
        }
    }

    @Nested
    class FreshnessParsing {
        @Test
        void shouldParseDay() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "DAY", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.DAY);
        }

        @Test
        void shouldParseMonth() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "MONTH", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.MONTH);
        }

        @Test
        void shouldParseYear() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "YEAR", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.YEAR);
        }

        @Test
        void shouldDefaultToAny() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.ANY);
        }

        @Test
        void shouldMapUnknownToAny() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "WEEK", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.ANY);
        }

        @Test
        void shouldParseCaseInsensitive() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "  day  ", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.freshness()).isEqualTo(WebSearchRequest.Freshness.DAY);
        }
    }

    @Nested
    class SearxngTimeRange {
        @Test
        void shouldReturnNullForAny() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "ANY", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.searxngTimeRange()).isNull();
        }

        @Test
        void shouldReturnLowercaseForDay() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "DAY", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            assertThat(req.searxngTimeRange()).isEqualTo("day");
        }
    }

    @Nested
    class DomainParsing {
        @Test
        void shouldParseCommaSeparated() {
            assertThat(WebSearchRequest.parseDomains("spring.io,docs.oracle.com"))
                    .containsExactly("spring.io", "docs.oracle.com");
        }

        @Test
        void shouldTrimAndLowercase() {
            assertThat(WebSearchRequest.parseDomains("  Spring.IO  ,  Docs.Oracle.COM  "))
                    .containsExactly("spring.io", "docs.oracle.com");
        }

        @Test
        void shouldDeduplicate() {
            assertThat(WebSearchRequest.parseDomains("example.com,EXAMPLE.COM"))
                    .containsExactly("example.com");
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(WebSearchRequest.parseDomains(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(WebSearchRequest.parseDomains("   ")).isEmpty();
        }

        @Test
        void shouldFilterInvalidHostnames() {
            assertThat(WebSearchRequest.parseDomains("valid.com,not valid,also-valid.org"))
                    .containsExactly("valid.com", "also-valid.org");
        }
    }

    @Nested
    class HostnameValidation {
        @Test
        void shouldAcceptValid() {
            assertThat(WebSearchRequest.isValidHostname("example.com")).isTrue();
            assertThat(WebSearchRequest.isValidHostname("sub.example.com")).isTrue();
            assertThat(WebSearchRequest.isValidHostname("my-site.org")).isTrue();
            assertThat(WebSearchRequest.isValidHostname("a.co")).isTrue();
        }

        @Test
        void shouldRejectInvalid() {
            assertThat(WebSearchRequest.isValidHostname("")).isFalse();
            assertThat(WebSearchRequest.isValidHostname(".")).isFalse();
            assertThat(WebSearchRequest.isValidHostname(".example.com")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("-example.com")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("example.com-")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("exa mple.com")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("example.com/path")).isFalse();
        }

        @Test
        void shouldRejectTrailingDot() {
            assertThat(WebSearchRequest.isValidHostname("example.com.")).isFalse();
        }

        @Test
        void shouldRejectLabelsStartingWithHyphen() {
            assertThat(WebSearchRequest.isValidHostname("bad-.example.com")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("-label.com")).isFalse();
        }

        @Test
        void shouldRejectLabelsEndingWithHyphen() {
            assertThat(WebSearchRequest.isValidHostname("good.-bad.com")).isFalse();
            assertThat(WebSearchRequest.isValidHostname("label-.com")).isFalse();
        }

        @Test
        void shouldRejectTooLongLabel() {
            String longLabel = "a".repeat(64);
            assertThat(WebSearchRequest.isValidHostname(longLabel + ".com")).isFalse();
        }
    }
}
