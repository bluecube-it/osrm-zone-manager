package it.bluecube.osrmzonemanager.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration class for creating and managing the proxy HTTP client used to interact with external services.
 * <p>
 * This configuration defines a {@link RestClient} bean customized with timeout settings for establishing connections
 * and reading data. The client is optimized for scenarios involving long-running operations and slow network connections,
 * providing better resilience and reliability when interacting with proxy services.
 * <p>
 * Components defined in this configuration can be injected into other Spring-managed components or services
 * wherever a RestClient instance is required for proxy communication.
 */
@Configuration
public class ProxyClientConfig {
    @Bean
    public RestClient proxyRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMinutes(2));
        factory.setReadTimeout(Duration.ofMinutes(2));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
