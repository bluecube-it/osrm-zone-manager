package it.bluecube.osrmzonemanager.maps;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class MapsClientConfig {
    @Bean
    public RestClient mapsRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMinutes(20));
        factory.setReadTimeout(Duration.ofMinutes(20));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
