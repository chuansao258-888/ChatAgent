package com.yulong.chatagent.support.persistence.adapter.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryItemMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MyBatisMemoryItemRepositoryPostgresIntegrationTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    private static JdbcTemplate jdbcTemplate;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema(dataSource);
        sqlSessionFactory = buildSqlSessionFactory(dataSource);
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE memory_item, memory_extraction_log, t_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO t_user (id, username, password_hash, role)
                VALUES (CAST(? AS uuid), ?, ?, 'user')
                """, USER_ID, "memory-user", "password-hash");
    }

    @Test
    void shouldUsePostgresOnConflictToUpdateDuplicateHash() {
        MemoryItemDTO first = MemoryItemDTO.builder()
                .userId(USER_ID)
                .type("fact")
                .content("User works on ChatAgent L3 memory")
                .contentHash("same-hash")
                .tags(List.of("initial"))
                .source(Map.of("session_id", "session-1"))
                .indexStatus("indexed")
                .build();

        MemoryItemDTO inserted = upsert(first);

        MemoryItemDTO duplicate = MemoryItemDTO.builder()
                .userId(USER_ID)
                .type("fact")
                .content("Duplicate evidence should not create a new row")
                .contentHash("same-hash")
                .tags(List.of("latest"))
                .source(Map.of("session_id", "session-2"))
                .build();

        MemoryItemDTO updated = upsert(duplicate);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(updated.getContent()).isEqualTo("User works on ChatAgent L3 memory");
        assertThat(updated.getTags()).containsExactly("latest");
        assertThat(updated.getSource()).containsEntry("session_id", "session-2");
        assertThat(currentIndexStatus()).isEqualTo("indexed");
    }

    @Test
    void shouldCreateOneRowForConcurrentDuplicateUpserts() throws Exception {
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MemoryItemDTO>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                int attempt = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }
                    return upsert(MemoryItemDTO.builder()
                            .userId(USER_ID)
                            .type("preference")
                            .content("User prefers concise answers")
                            .contentHash("concurrent-hash")
                            .tags(List.of("attempt-" + attempt))
                            .source(Map.of("session_id", "session-" + attempt))
                            .build());
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<MemoryItemDTO> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(rowCount()).isEqualTo(1);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("""
                CREATE TABLE t_user (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    username VARCHAR(255) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    role VARCHAR(50) NOT NULL DEFAULT 'user',
                    avatar VARCHAR(500),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    CONSTRAINT uk_t_user_username UNIQUE (username),
                    CONSTRAINT ck_t_user_role CHECK (role IN ('admin', 'user'))
                )
                """);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20__l3_long_term_memory.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) throws Exception {
        Environment environment = new Environment("postgres-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        try (InputStream mapperXml = Resources.getResourceAsStream("mapper/MemoryItemMapper.xml")) {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    mapperXml,
                    configuration,
                    "mapper/MemoryItemMapper.xml",
                    configuration.getSqlFragments()
            );
            mapperBuilder.parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static MemoryItemDTO upsert(MemoryItemDTO item) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            MemoryItemMapper mapper = sqlSession.getMapper(MemoryItemMapper.class);
            MyBatisMemoryItemRepository repository = new MyBatisMemoryItemRepository(mapper, OBJECT_MAPPER);
            return repository.upsert(item);
        }
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_item", Integer.class);
    }

    private String currentIndexStatus() {
        return jdbcTemplate.queryForObject("SELECT index_status FROM memory_item", String.class);
    }
}
