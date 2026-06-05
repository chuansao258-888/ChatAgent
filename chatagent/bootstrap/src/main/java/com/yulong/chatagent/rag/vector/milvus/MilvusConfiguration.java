package com.yulong.chatagent.rag.vector.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus client wiring guarded behind the {@code milvus.enabled} flag.
 */
@Configuration
@EnableConfigurationProperties({MilvusProperties.class, KnowledgeBaseMilvusProperties.class, UserMemoryMilvusProperties.class})
public class MilvusConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true")
    public MilvusClientV2 milvusClientV2(MilvusProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri("http://" + properties.getHost() + ":" + properties.getPort());

        String username = properties.getUsername();
        String password = properties.getPassword();
        if (username != null && !username.isBlank()) {
            builder.token(username + ":" + (password == null ? "" : password));
        }

        return new MilvusClientV2(builder.build());
    }
}
