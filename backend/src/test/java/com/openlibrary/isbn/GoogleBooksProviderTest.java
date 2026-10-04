package com.openlibrary.isbn;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google Books parsing, served from a local stub.
 *
 * <p>The live API answers 429 from a shared IP quota, which is exactly why this suite
 * must not touch it. The fixture follows the documented Volume resource shape.
 */
class GoogleBooksProviderTest {

    private WireMockServer stub;
    private GoogleBooksProvider provider;

    @BeforeEach
    void startStub() {
        stub = new WireMockServer(options().dynamicPort());
        stub.start();
        provider = new GoogleBooksProvider(
                RestClient.builder().baseUrl("http://localhost:" + stub.port()).build(), null);
    }

    @AfterEach
    void stopStub() {
        stub.stop();
    }

    @Test
    void mapsTheVolumeOntoTheCommonModel() {
        stubVolume(fixture("googlebooks-book.json"));

        Optional<ExternalBook> found = provider.lookup(Isbn.require("9780306406157"));

        assertThat(found).isPresent();
        ExternalBook book = found.orElseThrow();

        assertThat(book.title()).isEqualTo("Neuromancer");
        assertThat(book.subtitle()).isEqualTo("El primer caso de la Saga de la Maquina de Turing");
        assertThat(book.authors()).containsExactly("William Gibson");
        assertThat(book.publisher()).isEqualTo("Ace");
        assertThat(book.publicationYear()).isEqualTo(1984);
        assertThat(book.categories()).containsExactly("Science fiction", "Cyberpunk");
        assertThat(book.pages()).isEqualTo(271);
        assertThat(book.language()).isEqualTo("en");
        assertThat(book.summary()).isEqualTo(
                "Case, the cyborg who was the best nerve-dispatcher in the business.");
        assertThat(book.source()).isEqualTo("googlebooks");
        assertThat(book.isbn()).isEqualTo("9780306406157");
    }

    @Test
    void prefersTheLargestCoverAvailable() {
        stubVolume(fixture("googlebooks-book.json"));

        ExternalBook book = provider.lookup(Isbn.require("9780306406157")).orElseThrow();

        assertThat(book.coverUrl()).contains("zoom=4");
    }

    @Test
    void fallsBackToASmallerCoverWhenThereIsNoLargeOne() {
        stubVolume("""
                {"totalItems": 1, "items": [{"volumeInfo": {
                   "title": "Neuromancer",
                   "imageLinks": {"smallThumbnail": "https://example.test/s.jpg"}
                }}]}""");

        ExternalBook book = provider.lookup(Isbn.require("9780306406157")).orElseThrow();

        assertThat(book.coverUrl()).isEqualTo("https://example.test/s.jpg");
    }

    @Test
    void returnsEmptyWhenTheSearchHasNoResults() {
        stubVolume(fixture("googlebooks-empty.json"));

        assertThat(provider.lookup(Isbn.require("9780306406157"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenGoogleAnswersAnError() {
        // Quota, auth failure, anything: the caller must still be able to type the book.
        stub.stubFor(get(urlPathMatching("/books/v1/volumes.*"))
                .willReturn(aResponse().withStatus(403)));

        assertThat(provider.lookup(Isbn.require("9780306406157"))).isEmpty();
    }

    @Test
    void neverTouchesTheNetworkForAnIsbnThatFailedItsChecksum() {
        assertThat(provider.lookup(Isbn.parse("9780306406158"))).isEmpty();
        stub.verify(0, getRequestedFor(urlPathMatching("/books/v1/volumes.*")));
    }

    @Test
    void readsTheYearFromAPartialPublishedDate() {
        assertThat(GoogleBooksProvider.year("1984-07-01")).isEqualTo(1984);
        assertThat(GoogleBooksProvider.year("1984")).isEqualTo(1984);
        assertThat(GoogleBooksProvider.year(null)).isNull();
    }

    @Test
    void worksWithoutAnApiKeySoSelfHostedNeedsNoSignup() {
        stubVolume(fixture("googlebooks-book.json"));
        provider = new GoogleBooksProvider(
                RestClient.builder().baseUrl("http://localhost:" + stub.port()).build(), null);

        assertThat(provider.lookup(Isbn.require("9780306406157"))).isPresent();
        stub.verify(getRequestedFor(urlPathEqualTo("/books/v1/volumes")));
    }

    @Test
    void sendsTheApiKeyOnlyWhenOneIsConfigured() {
        stubVolume(fixture("googlebooks-book.json"));
        provider = new GoogleBooksProvider(
                RestClient.builder().baseUrl("http://localhost:" + stub.port()).build(), "secret");

        provider.lookup(Isbn.require("9780306406157"));

        stub.verify(getRequestedFor(urlPathEqualTo("/books/v1/volumes"))
                .withQueryParam("key", com.github.tomakehurst.wiremock.client.WireMock.equalTo("secret")));
    }

    private void stubVolume(String body) {
        stub.stubFor(get(urlPathEqualTo("/books/v1/volumes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }

    private static String fixture(String name) {
        try (InputStream in = GoogleBooksProviderTest.class.getResourceAsStream("/isbn/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
