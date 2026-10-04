package com.openlibrary.isbn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Wiring for the ISBN providers.
 *
 * <p>The clients are built here rather than injected: Spring Boot does not
 * auto-configure a {@code RestClient.Builder}, and a self-hosted instance behind a
 * slow link needs a real timeout instead of hanging a request thread forever.
 *
 * <p>Order matters: Open Library first because it needs no key, Google Books second
 * because it usually has a cover and a summary. Google Books is only added when a key
 * is configured, so an instance without one never calls it.
 */
@Configuration
class IsbnProviderConfig {

    static final String OPEN_LIBRARY_BASE = "https://openlibrary.org";
    static final String GOOGLE_BOOKS_BASE = "https://www.googleapis.com";

    /** 5s: a librarian is waiting on the screen. Failing fast lets them type the book. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    RestClient openLibraryRestClient() {
        return restClient(OPEN_LIBRARY_BASE);
    }

    @Bean
    RestClient googleBooksRestClient() {
        return restClient(GOOGLE_BOOKS_BASE);
    }

    @Bean
    OpenLibraryProvider openLibraryProvider(RestClient openLibraryRestClient) {
        return new OpenLibraryProvider(openLibraryRestClient);
    }

    @Bean
    GoogleBooksProvider googleBooksProvider(
            RestClient googleBooksRestClient,
            @Value("${isbn.google-books-key:}") String apiKey) {
        return new GoogleBooksProvider(googleBooksRestClient, apiKey);
    }

    /**
     * Google Books works keyless for small volumes, so the provider is always
     * registered; the key only raises the quota when one is configured.
     */
    @Bean
    IsbnProviderChain isbnProviderChain(IsbnCache cache,
                                       OpenLibraryProvider openLibrary,
                                       GoogleBooksProvider googleBooks) {
        return new IsbnProviderChain(cache, List.of(openLibrary, googleBooks));
    }

    private static RestClient restClient(String baseUrl) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
