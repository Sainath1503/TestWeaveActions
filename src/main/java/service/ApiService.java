package service;

import io.restassured.RestAssured;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import model.ApiRequest;
import model.ApiResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

public class ApiService {

    public ApiResponse sendRequest(ApiRequest request) {
        int timeoutMs = httpTimeoutMs();
        RestAssuredConfig config = RestAssured.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMs)
                        .setParam("http.socket.timeout", timeoutMs)
                        .setParam("http.connection-manager.timeout", (long) timeoutMs)
        );

        io.restassured.specification.RequestSpecification req = RestAssured.given()
                .config(config)
                .header("User-Agent", "API-Validator-Tool/1.0");

        if (request.headers != null && !request.headers.isEmpty()) {
            req.headers(request.headers);
        }

        if (request.token != null && !request.token.isBlank()) {
            req.header("Authorization", "Bearer " + request.token);
        }

        if (request.body != null && !request.body.isBlank()
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
