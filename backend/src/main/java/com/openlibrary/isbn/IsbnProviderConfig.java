package com.openlibrary.isbn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wiring for the ISBN providers.
 *
 * <p>The client is built here rather than injected: Spring Boot does not
 * auto-configure a {@code RestClient.Builder}, and a self-hosted instance behind a
 * slow link needs a real timeout instead of hanging a request thread forever.
 *
 * <p>ponytail: one base URL constant. Make it configurable when a second provider
 * actually needs it.
 */
@Configuration
class IsbnProviderConfig {

    static final String OPEN_LIBRARY_BASE = "https://openlibrary.org";

    /** 5s: a librarian is waiting on the screen. Failing fast lets them type the book. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    RestClient openLibraryRestClient() {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder()
                .baseUrl(OPEN_LIBRARY_BASE)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    OpenLibraryProvider openLibraryProvider(RestClient openLibraryRestClient) {
        return new OpenLibraryProvider(openLibraryRestClient);
    }
}
