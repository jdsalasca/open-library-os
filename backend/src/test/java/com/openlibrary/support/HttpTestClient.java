package com.openlibrary.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Drives the real HTTP stack with a real cookie jar, so tests exercise session
 * handling, CSRF and authorisation exactly as a browser would.
 */
public final class HttpTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final CookieManager cookies;
    private final String baseUrl;

    public HttpTestClient(int port) {
        this.cookies = new CookieManager();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cookies)
                .build();
        this.baseUrl = "http://localhost:" + port + "/api";
    }

    public record Result(int status, String body, JsonNode json) {
        public String text(String field) {
            return json.path(field).asText();
        }
    }

    public Result get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Result post(String path, Object body) {
        return send(write(HttpRequest.newBuilder(uri(path)), "POST", body));
    }

    public Result put(String path, Object body) {
        return send(write(HttpRequest.newBuilder(uri(path)), "PUT", body));
    }

    public Result patch(String path, Object body) {
        return send(write(HttpRequest.newBuilder(uri(path)), "PATCH", body));
    }

    /** Binary payload (labels, covers): a text body would mangle the bytes. */
    public record BinaryResult(int status, byte[] bytes, String contentType) {
    }

    public BinaryResult getBytes(String path) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new BinaryResult(response.statusCode(), response.body(),
                    String.valueOf(response.headers().firstValue("content-type").orElse("")));
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + e, e);
        }
    }

    /** DELETE is a state-changing verb, so it needs the CSRF header like the rest. */
    public Result delete(String path) {
        return send(write(HttpRequest.newBuilder(uri(path)), "DELETE", null));
    }

    /** Sends a write without the CSRF token, to prove the backend rejects it. */
    public Result postWithoutCsrf(String path, Object body) {
        var publisher = HttpRequest.BodyPublishers.ofString(payload(body));
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(publisher));
    }

    /**
     * Reads the raw CSRF token from the cookie, exactly as the browser does.
     * The value returned by GET /auth/csrf is masked for a given request and would
     * be rejected, so the cookie is the only source of truth.
     */
    public String csrfToken() {
        get("/auth/csrf");
        return cookies.getCookieStore().get(URI.create(baseUrl + "/auth/csrf")).stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(c -> c.getValue())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no XSRF-TOKEN cookie was set"));
    }

    private HttpRequest.Builder write(HttpRequest.Builder builder, String method, Object body) {
        return builder
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .method(method, HttpRequest.BodyPublishers.ofString(payload(body)));
    }

    private Result send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(
                    builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body(), parse(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + e, e);
        }
    }

    private URI uri(String path) {
        // Spaces are not legal in a URI; encode them so tests can type
        // "?q=cien anios" without thinking about it.
        return URI.create(baseUrl + path.replace(" ", "%20"));
    }

    private static String payload(Object body) {
        return body == null ? "{}" : JSON.writeValueAsString(body);
    }

    private static JsonNode parse(String body) {
        try {
            return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}