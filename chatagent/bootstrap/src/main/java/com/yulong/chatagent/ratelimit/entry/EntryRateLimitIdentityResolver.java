package com.yulong.chatagent.ratelimit.entry;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves the rate-limit identity for a chat entry request.
 *
 * <p>Authenticated user ID is the primary key; normalized client IP is the
 * fallback when no user is present. The resolved key is always hashed so raw
 * user IDs and IP addresses never appear in Redis keys or logs.</p>
 *
 * <p>中文说明：入口限流身份解析。优先用已认证用户 ID，无用户时回退到
 * 归一化客户端 IP。结果一律 SHA-256 哈希，确保原始值不出现在 Redis 键里。</p>
 */
@Component
public class EntryRateLimitIdentityResolver {

    private static final String[] FORWARDED_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP"
    };

    /**
     * Resolves the rate-limit identity for the current request.
     *
     * @param request current HTTP request used for IP fallback
     * @return resolved identity, never {@code null}
     */
    public ResolvedIdentity resolve(HttpServletRequest request) {
        LoginUser user = UserContext.get();
        if (user != null && StringUtils.hasText(user.getUserId())) {
            return new ResolvedIdentity(
                    "user",
                    sha256("user:" + user.getUserId())
            );
        }
        return new ResolvedIdentity(
                "ip",
                sha256("ip:" + normalizeClientIp(request))
        );
    }

    private String normalizeClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        for (String header : FORWARDED_HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For may contain a list; take the first hop.
                String first = value.split(",")[0].trim();
                if (StringUtils.hasText(first)) {
                    return normalizeIp(first);
                }
            }
        }
        return normalizeIp(request.getRemoteAddr());
    }

    private String normalizeIp(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "unknown";
        }
        String ip = raw.trim();
        // Collapse IPv6 brackets and lower-case for stable hashing.
        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }
        // Map IPv4-mapped IPv6 (::ffff:1.2.3.4) to the plain IPv4 form.
        int mappedIndex = ip.indexOf(":ffff:");
        if (mappedIndex >= 0) {
            String mapped = ip.substring(mappedIndex + 6);
            if (mapped.contains(".") && !mapped.contains(":")) {
                ip = mapped;
            }
        }
        return ip.toLowerCase();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK; fall back to a stable identity
            // rather than failing the request path.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Resolved rate-limit identity.
     *
     * @param scope {@code user} or {@code ip}
     * @param key   hashed identity value used as the Redis key suffix
     */
    public record ResolvedIdentity(String scope, String key) {
    }
}
