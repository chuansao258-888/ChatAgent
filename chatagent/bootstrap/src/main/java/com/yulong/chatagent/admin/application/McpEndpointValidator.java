package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.enums.McpProtocol;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates administrator-supplied MCP endpoints before they are persisted.
 */
@Component
public class McpEndpointValidator {

    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "169.254.170.2",
            "100.100.100.200",
            "metadata.google.internal"
    );
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private final Environment environment;

    public McpEndpointValidator(Environment environment) {
        this.environment = environment;
    }

    public String validateAndNormalize(McpProtocol protocol, String endpointUrl) {
        if (protocol == null) {
            throw new BizException("MCP protocol is required");
        }
        if (!StringUtils.hasText(endpointUrl)) {
            throw new BizException("MCP endpoint URL is required");
        }

        URI uri = parse(endpointUrl.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("MCP endpoint must use http or https");
        }
        if (!StringUtils.hasText(host)) {
            throw new BizException("MCP endpoint host is required");
        }
        if (StringUtils.hasText(uri.getUserInfo())) {
            throw new BizException("MCP endpoint must not embed credentials in the URL");
        }

        boolean allowLocalHttp = isDevelopmentProfile();
        if ("http".equals(scheme) && !(allowLocalHttp && isAllowedLocalHost(host))) {
            throw new BizException("Only https endpoints are allowed outside local development");
        }

        validateHost(host, allowLocalHttp);
        return uri.normalize().toString();
    }

    private void validateHost(String host, boolean allowLocalHttp) {
        if (METADATA_HOSTS.contains(host)) {
            throw new BizException("Metadata endpoints are not allowed for MCP configuration");
        }
        if ("localhost".equals(host)) {
            if (!allowLocalHttp) {
                throw new BizException("Loopback endpoints are only allowed in local development");
            }
            return;
        }
        if (looksLikeLiteralAddress(host)) {
            InetAddress address = parseAddress(host);
            if (isDisallowedAddress(address, allowLocalHttp, host)) {
                throw new BizException("Private, loopback, or link-local addresses are not allowed for MCP configuration");
            }
            return;
        }
        if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".corp")) {
            throw new BizException("Internal hostnames are not allowed for MCP configuration");
        }
    }

    private boolean isDevelopmentProfile() {
        return environment.acceptsProfiles(Profiles.of("default", "dev", "local", "test"));
    }

    private boolean isAllowedLocalHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host);
    }

    private boolean looksLikeLiteralAddress(String host) {
        return IPV4_PATTERN.matcher(host).matches() || host.contains(":");
    }

    private InetAddress parseAddress(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (Exception ex) {
            throw new BizException("Invalid MCP endpoint host: " + host);
        }
    }

    private boolean isDisallowedAddress(InetAddress address, boolean allowLocalHttp, String originalHost) {
        if (METADATA_HOSTS.contains(originalHost)) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address.isLoopbackAddress()) {
            return !(allowLocalHttp && "127.0.0.1".equals(originalHost));
        }
        return false;
    }

    private URI parse(String endpointUrl) {
        try {
            return new URI(endpointUrl);
        } catch (URISyntaxException ex) {
            throw new BizException("Invalid MCP endpoint URL: " + endpointUrl);
        }
    }
}
