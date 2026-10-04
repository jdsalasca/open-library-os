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
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Open Library parsing, served from a local stub.
 *
 * <p>A test that reached the real api.openlibrary.org would be slow, flaky, and would
 * make the suite depend on someone else's uptime. The fixture mirrors the shape the API
 * actually returns for {@code jscmd=data}: no languages, no description unless sent.
 */
class OpenLibraryProviderTest {

    private WireMockServer stub;
    private OpenLibraryProvider provider;

    @BeforeEach
    void startStub() {
        stub = new WireMockServer(options().dynamicPort());
        stub.start();
        provider = new OpenLibraryProvider(
                RestClient.builder().baseUrl("http://localhost:" + stub.port()).build());
    }

    @AfterEach
    void stopStub() {
        stub.stop();
    }

    @Test
    void mapsThePayloadOntoTheCommonModel() {
        stubBooks(fixture("openlibrary-book.json"));

        Optional<ExternalBook> found = provider.lookup(Isbn.require("9780306406157"));

        assertThat(found).isPresent();
        ExternalBook book = found.orElseThrow();

        assertThat(book.title()).isEqualTo("Neuromancer");
        assertThat(book.authors()).containsExactly("William Gibson");
        assertThat(book.publisher()).isEqualTo("Ace");
        assertThat(book.publicationYear()).isEqualTo(1984);
        assertThat(book.categories())
                .containsExactly("Science fiction", "Cyberpunk", "Dystopias");
        assertThat(book.pages()).isEqualTo(271);
        assertThat(book.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/1234567-L.jpg");
        assertThat(book.source()).isEqualTo("openlibrary");
        assertThat(book.isbn()).isEqualTo("9780306406157");
    }

    @Test
    void doesNotInventFieldsTheProviderOmitted() {
        stubBooks(fixture("openlibrary-book.json"));

        ExternalBook book = provider.lookup(Isbn.require("9780306406157")).orElseThrow();

        // jscmd=data carries neither languages nor a description.
        assertThat(book.language()).isNull();
        assertThat(book.summary()).isNull();
    }

    @Test
    void readsTheSummaryWhenTheProviderSendsOne() {
        stubBooks("""
                {"ISBN:9780306406157": {
                   "title": "Neuromancer",
                   "description": { "value": "Case, the cyborg." }
                }}""");

        ExternalBook book = provider.lookup(Isbn.require("9780306406157")).orElseThrow();

        assertThat(book.summary()).isEqualTo("Case, the cyborg.");
    }

    @Test
    void survivesASparsePayloadWithoutInventingValues() {
        stubBooks(fixture("openlibrary-sparse.json"));

        ExternalBook book = provider.lookup(Isbn.require("9780306406157")).orElseThrow();

        assertThat(book.title()).isEqualTo("Neuromancer");
        assertThat(book.authors()).isEmpty();
        assertThat(book.categories()).isEmpty();
        assertThat(book.summary()).isNull();
        assertThat(book.coverUrl()).isNull();
        assertThat(book.publicationYear()).isNull();
        assertThat(book.pages()).isZero(); // the provider claims zero, which is not "unknown"
    }

    @Test
    void returnsEmptyWhenTheBookIsUnknownInsteadOfThrowing() {
        stubBooks("{\"ISBN:9780306406157\": {}}");

        assertThat(provider.lookup(Isbn.require("9780306406157"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheProviderIsDown() {
        stub.stubFor(get(urlPathEqualTo("/api/books")).willReturn(aResponse().withStatus(500)));

        assertThat(provider.lookup(Isbn.require("9780306406157"))).isEmpty();
    }

    @Test
    void neverTouchesTheNetworkForAnIsbnThatFailedItsChecksum() {
        assertThat(provider.lookup(Isbn.parse("9780306406158"))).isEmpty();
        stub.verify(0, getRequestedFor(urlPathEqualTo("/api/books")));
    }

    @Test
    void readsTheYearFromAPartialPublishDate() {
        assertThat(OpenLibraryProvider.year("1984-07")).isEqualTo(1984);
        assertThat(OpenLibraryProvider.year("1984")).isEqualTo(1984);
        assertThat(OpenLibraryProvider.year("n.d.")).isNull();
        assertThat(OpenLibraryProvider.year(null)).isNull();
    }

    private void stubBooks(String body) {
        // The content type matters: without it RestClient hands back an empty body and
        // the provider looks like it "found nothing", which is a confusing way to fail.
        stub.stubFor(get(urlPathEqualTo("/api/books"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }

    private static String fixture(String name) {
        try (InputStream in = OpenLibraryProviderTest.class.getResourceAsStream("/isbn/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
