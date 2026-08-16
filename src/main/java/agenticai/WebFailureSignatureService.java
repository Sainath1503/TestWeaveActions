package agenticai;

import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class WebFailureSignatureService {
    public String signature(JSONObject scope, JSONObject step, JSONObject failure) {
        JSONObject safe = new JSONObject()
                .put("projectId", clean(scope.optString("projectId")))
                .put("workflowId", clean(scope.optString("workflowId")))
                .put("domain", domain(scope.optString("startUrl")))
                .put("path", path(scope.optString("startUrl")))
                .put("testName", clean(scope.optString("testName")))
                .put("action", clean(first(failure.optString("action"), step.optString("action"))))
                .put("selector", clean(first(failure.optString("selector"), step.optString("selector"))))
                .put("failureType", failureType(failure))
                .put("expected", clean(first(failure.optString("expected"), step.optString("value"))))
                .put("flowVariableName", clean(step.optString("flowVariableName")))
                .put("semanticElement", semanticElement(step, failure));
        return sha256(safe.toString());
    }

    public String failureType(JSONObject failure) {
        String text = (failure.optString("message") + " " + failure.optString("action")).toLowerCase(Locale.ROOT);
        if (text.contains("timeout")) return "timeoutFailure";
        if (text.contains("strict mode") || text.contains("multiple") || text.contains("duplicate")) return "duplicateLocatorMatch";
        if (text.contains("not enabled")) return "elementNotEnabled";
        if (text.contains("not visible") || text.contains("visible")) return "elementNotVisible";
        if (text.contains("not found") || text.contains("no element") || text.contains("missing element")) return "missingElement";
        if (text.contains("expected") && text.contains("text")) return "incorrectExpectedText";
        if (text.contains("expected") || text.contains("actual")) return "incorrectExpectedValue";
        if (text.contains("date") && text.contains("format")) return "dateFormatMismatch";
        if (text.contains("case") || text.contains("whitespace") || text.contains("trim")) return "formatMismatch";
        if (text.contains("url") || text.contains("navigation")) return "navigationMismatch";
        if (text.contains("flow variable")) return "flowVariableFailure";
        if (text.contains("variable")) return "variableFailure";
        if (text.contains("wrong page") || text.contains("page state")) return "wrongPageState";
        if (text.contains("action")) return "incorrectAction";
        if (text.contains("selector") || text.contains("locator") || text.contains("element")) return "locatorFailure";
        return "scriptFailure";
    }

    private String semanticElement(JSONObject step, JSONObject failure) {
        return clean(first(step.optString("stepName"), step.optString("note"), failure.optString("stepName")));
    }

    private String domain(String url) {
        try { return clean(URI.create(url).getHost()); } catch (Exception ignored) { return ""; }
    }

    private String path(String url) {
        try { return clean(URI.create(url).getPath()); } catch (Exception ignored) { return ""; }
    }

    private String clean(String value) {
        String safe = value == null ? "" : value;
        safe = safe.replaceAll("(?i)(bearer\\s+)[a-z0-9._~+/=-]+", "$1[redacted]")
                .replaceAll("(?i)(password|token|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]");
        return safe.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not build Web failure signature", exception);
        }
    }
}
