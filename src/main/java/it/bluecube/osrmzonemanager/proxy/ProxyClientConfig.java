package it.bluecube.osrmzonemanager.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Configuration class for the proxy-to-zone HTTP client.
 * <p>
 * This {@link RestClient} bean is intentionally scoped only to forwarding requests to zone OSRM/VROOM
 * subprocesses. It does not throw on 4xx/5xx responses so that the origin response is returned
 * unchanged to the caller.
 */
@Configuration
public class ProxyClientConfig {
    @Bean
    public RestClient proxyRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMinutes(2));
        factory.setReadTimeout(Duration.ofMinutes(2));

        Predicate<HttpStatusCode> isErrorResponse = HttpStatusCode::isError;
        RestClient.ResponseSpec.ErrorHandler noop = (request, response) -> {
            // No-op: don't throw. Body already consumed by retrieve().toEntity().
        };

        return RestClient.builder()
                .requestFactory(factory)
                .defaultStatusHandler(isErrorResponse, noop)
                .build();
    }
}
