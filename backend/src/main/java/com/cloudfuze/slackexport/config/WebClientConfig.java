package com.cloudfuze.slackexport.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Value("${slack.api.base-url:https://slack.com/api}")
    private String slackBaseUrl;

    /** Connect timeout (ms) for Slack API calls; avoids hanging on DNS/TCP and reduces Netty flush errors. */
    @Value("${slack.api.connect-timeout-ms:30000}")
    private int slackApiConnectTimeoutMs;


    @Bean
    public WebClient slackWebClient(WebClient.Builder builder) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        int bufferSizeBytes = 16 * 1024 * 1024; // 16MB — avoid "max bytes to buffer : 262144" on large Slack responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(bufferSizeBytes);
                    configurer.defaultCodecs()
                            .jackson2JsonDecoder(new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
                })
                .build();

        // Connect timeout so DNS/TCP connect failures fail fast instead of hanging (avoids Netty flush errors on close)
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, slackApiConnectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(60))
                .doOnConnected(conn -> conn.addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(60))
                        .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(60)));

        return builder
                .baseUrl(slackBaseUrl)
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** Used by Spring MVC for @RequestBody; keep default camelCase so frontend JSON binds correctly. */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
