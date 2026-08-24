package dev.sorokin.eventmanager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TestConfiguration {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserTestConfiguration userTestConfiguration;

    private static volatile boolean isSharedSetupDone = false;

    public static PostgreSQLContainer<?> POSTGRES_CONTAINER =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("postgres_event-manager")
                    .withUsername("postgres_event-manager")
                    .withPassword("root");

    public static GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7")
                    .withExposedPorts(6379);

    public static KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        if (!isSharedSetupDone) {
            POSTGRES_CONTAINER.start();
            REDIS_CONTAINER.start();
            KAFKA_CONTAINER.start();
            isSharedSetupDone = true;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("test.postgres_event-manager.port", POSTGRES_CONTAINER::getFirstMappedPort);

        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);

        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
    }

    @EventListener
    public void stopContainer() {
        POSTGRES_CONTAINER.stop();
        REDIS_CONTAINER.stop();
        KAFKA_CONTAINER.stop();
    }

}