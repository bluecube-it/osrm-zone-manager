package it.bluecube.osrmzonemanager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async configuration for zone-manager background work.
 *
 * <p>Provides a dedicated {@link Executor} bean named {@code zoneManagerTaskExecutor}
 * backing {@code BootRecoveryService} and {@code ZoneService} async operations.
 * Threads are virtual (JEP 444) to keep the runtime light under I/O-bound workloads.
 */
@Configuration
public class AsyncConfig {

    /**
     * Executor for all zone-manager background tasks (recovery, post-build start).
     *
     * @return a {@link SimpleAsyncTaskExecutor} spawning virtual threads
     * with a {@code zone-mgr-} name prefix for log and thread-dump readability
     */
    @Bean
    public Executor zoneManagerTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("zone-mgr-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
