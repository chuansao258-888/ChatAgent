package com.yulong.chatagent.support.chat;

import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import io.netty.channel.ChannelOption;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Applies configured connect and read timeouts to the HTTP clients used by chat model calls, for
 * both the blocking {@code RestClient} and the reactive {@code WebClient}. Timeouts are sourced
 * from {@link ChatRoutingProperties}.
 */
@Configuration
public class ChatModelHttpClientTimeoutConfig {

    @Bean
    RestClientCustomizer chatModelRestClientTimeoutCustomizer(ChatRoutingProperties properties) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getHttpConnectTimeoutSeconds()));
            requestFactory.setReadTimeout(Duration.ofSeconds(properties.getHttpReadTimeoutSeconds()));
            builder.requestFactory(requestFactory);
        };
    }

    @Bean
    WebClientCustomizer chatModelWebClientTimeoutCustomizer(ChatRoutingProperties properties) {
        return builder -> {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getHttpConnectTimeoutSeconds() * 1000)
                    .responseTimeout(Duration.ofSeconds(properties.getHttpReadTimeoutSeconds()));
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        };
    }
}
