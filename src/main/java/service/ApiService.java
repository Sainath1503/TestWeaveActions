package service;

import io.restassured.RestAssured;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.specification.ProxySpecification;
import model.ApiRequest;
import model.ApiRequestBodyPart;
import model.ApiResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ApiService {

    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    public ApiResponse sendRequest(ApiRequest request) {
        int timeoutMs = httpTimeoutMs();
        RestAssuredConfig config = requestConfig(request, timeoutMs);

        io.restassured.specification.RequestSpecification req = RestAssured.given()
                .config(config)
                .header("User-Agent", "API-Validator-Tool/1.0");

        applyProxy(request, req);

        if (request.headers != null && !request.headers.isEmpty()) {
            req.headers(request.headers);
        }

        if (request.token != null && !request.token.isBlank()) {
            req.header("Authorization", "Bearer " + request.token);
        }

        applyCookies(request, req);

        if ("formdata".equalsIgnoreCase(request.bodyMode) && request.multipartParts != null && !request.multipartParts.isEmpty()) {
            for (ApiRequestBodyPart part : request.multipartParts) {
                if (part == null || part.name == null || part.name.isBlank()) {
                    continue;
                }
                if (part.file) {
                    File file = part.filePath == null ? null : Path.of(part.filePath).toFile();
                    if (file != null && file.isFile()) {
                        if (part.contentType == null || part.contentType.isBlank()) {
                            req.multiPart(part.name, file);
                        } else {
                            req.multiPart(part.name, file, part.contentType);
                        }
                    }
                } else {
                    req.multiPart(part.name, part.value == null ? "" : part.value);
                }
            }
        } else if ("binary".equalsIgnoreCase(request.bodyMode) && request.binaryFilePath != null && !request.binaryFilePath.isBlank()) {
            try {
                req.body(Files.readAllBytes(Path.of(request.binaryFilePath)));
            } catch (Exception e) {
                throw new IllegalArgumentException("Unable to read binary request body: " + request.binaryFilePath, e);
            }
        } else if (request.body != null && !request.body.isBlank()
                && !"GET".equalsIgnoreCase(request.method)
                && !"DELETE".equalsIgnoreCase(request.method)) {
            req.body(request.body);
        }

        Response response;
        switch (request.method.toUpperCase()) {
            case "POST":
                response = req.post(request.url);
                break;
            case "PUT":
                response = req.put(request.url);
                break;
            case "DELETE":
                response = req.delete(request.url);
                break;
            case "PATCH":
                response = req.patch(request.url);
                break;
            default:
                response = req.get(request.url);
        }

        storeCookies(request, response);

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.statusCode = response.statusCode();
        apiResponse.statusLine = response.statusCode() + " " + response.statusLine().replaceFirst("HTTP/\\S+\\s+", "");
        apiResponse.timeMs = response.time();
        apiResponse.rawBody = response.getBody().asString();
        apiResponse.prettyBody = prettyPrintJson(apiResponse.rawBody);
        apiResponse.headersText = buildHeadersText(response);
        apiResponse.cookiesText = buildCookiesText(response);
        apiResponse.sizeBytes = apiResponse.rawBody.getBytes().length;
        return apiResponse;
    }

    private RestAssuredConfig requestConfig(ApiRequest request, int timeoutMs) {
        RestAssuredConfig config = RestAssured.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMs)
                        .setParam("http.socket.timeout", timeoutMs)
                        .setParam("http.connection-manager.timeout", (long) timeoutMs)
        );

        SSLConfig ssl = SSLConfig.sslConfig();
        boolean sslConfigured = false;
        if (request != null && request.sslVerificationDisabled) {
            ssl = ssl.relaxedHTTPSValidation().allowAllHostnames();
            sslConfigured = true;
        }
        if (request != null && request.trustStorePath != null && !request.trustStorePath.isBlank()) {
            ssl = ssl.trustStore(new File(request.trustStorePath), nullToBlank(request.trustStorePassword));
            sslConfigured = true;
        }
        if (request != null && request.keyStorePath != null && !request.keyStorePath.isBlank()) {
            ssl = ssl.keyStore(new File(request.keyStorePath), nullToBlank(request.keyStorePassword));
            sslConfigured = true;
        }
        return sslConfigured ? config.sslConfig(ssl) : config;
    }

    private void applyProxy(ApiRequest request, io.restassured.specification.RequestSpecification req) {
        if (request == null || !request.proxyEnabled || request.proxyHost == null || request.proxyHost.isBlank()
                || request.proxyPort <= 0) {
            return;
        }
        ProxySpecification proxy = new ProxySpecification(request.proxyHost.trim(), request.proxyPort,
                firstNonBlank(request.proxyScheme, "http").trim());
        if (request.proxyUsername != null && !request.proxyUsername.isBlank()) {
            proxy = proxy.withAuth(request.proxyUsername, nullToBlank(request.proxyPassword));
        }
        req.proxy(proxy);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private void applyCookies(ApiRequest request, io.restassured.specification.RequestSpecification req) {
        try {
            Map<String, java.util.List<String>> cookieHeaders = cookieManager.get(URI.create(request.url), Map.of());
            for (Map.Entry<String, java.util.List<String>> entry : cookieHeaders.entrySet()) {
                for (String value : entry.getValue()) {
                    if (value != null && !value.isBlank()) {
                        req.header(entry.getKey(), value);
                    }
                }
            }
        } catch (Exception ignored) {
            // Cookie handling should never block a request that is otherwise valid.
        }
    }

    private void storeCookies(ApiRequest request, Response response) {
        try {
            Map<String, java.util.List<String>> headers = response.getHeaders().asList().stream()
                    .filter(header -> "Set-Cookie".equalsIgnoreCase(header.getName())
                            || "Set-Cookie2".equalsIgnoreCase(header.getName()))
                    .collect(java.util.stream.Collectors.groupingBy(
                            header -> header.getName(),
                            java.util.stream.Collectors.mapping(io.restassured.http.Header::getValue, java.util.stream.Collectors.toList())));
            if (!headers.isEmpty()) {
                cookieManager.put(URI.create(request.url), headers);
            }
        } catch (Exception ignored) {
            // The response remains valid even if cookie persistence fails.
        }
    }

    private int httpTimeoutMs() {
        String value = System.getProperty("testweave.http.timeout.ms");
        if (value == null || value.isBlank()) {
            value = System.getenv("TESTWEAVE_HTTP_TIMEOUT_MS");
        }
        if (value != null && !value.isBlank()) {
            try {
                return Math.max(1000, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                return 60000;
            }
        }
        return 60000;
    }

    public String prettyPrintJson(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return new JSONObject(trimmed).toString(2);
        }
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed).toString(2);
        }
        return text;
    }

    private String buildHeadersText(Response response) {
        StringBuilder builder = new StringBuilder();
        response.getHeaders().forEach(header ->
                builder.append(header.getName()).append(": ").append(header.getValue()).append(System.lineSeparator())
        );
        return builder.toString().trim();
    }

    private String buildCookiesText(Response response) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : response.getCookies().entrySet()) {
            Cookie cookie = response.detailedCookie(entry.getKey());
            builder.append(cookie.getName()).append("=").append(cookie.getValue());
            if (cookie.getDomain() != null) {
                builder.append("; Domain=").append(cookie.getDomain());
            }
            if (cookie.getPath() != null) {
                builder.append("; Path=").append(cookie.getPath());
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
