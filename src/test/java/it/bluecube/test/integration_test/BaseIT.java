package it.bluecube.test.integration_test;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.function.Supplier;

@Import(TestcontainersConfiguration.class)
@TestExecutionListeners(
        listeners = {DbCleaner.class},
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
@AutoConfigureRestTestClient
@EnableWireMock
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIT {

    @TempDir
    protected static Path tempDir;

    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected RestTestClient restTestClient;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("osrm.zone-manager.base-pbf", () -> tempDir.resolve("italy.osm.pbf").toString());
    }

    protected void runInTransaction(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    action.run();
                    return null;
                });
    }

    protected <T> T runInTransaction(Supplier<T> action) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> action.get());
    }
}
