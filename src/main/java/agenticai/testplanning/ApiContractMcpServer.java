package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.net.URI;

/** Normalizes OpenAPI/Swagger JSON and Postman collections into compact model context. */
public final class ApiContractMcpServer {
    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-api-contract-mcp")
                        .put("version", "1.0").put("mode", "in-process").put("readOnly", true))
                .put("tools", new JSONArray().put("api_contract.parse_openapi")
                        .put("api_contract.parse_postman").put("api_contract.list_operations"));
    }

    public JSONObject enrich(JSONObject source) {
        if (source == null) return null;
        String text = source == null ? "" : source.optString("content");
        enrichText(source, text);
        JSONArray linked = source.optJSONArray("linkedResources");
        JSONArray summaries = new JSONArray();
        JSONArray operations = new JSONArray();
        if (linked != null) {
            for (int i = 0; i < linked.length(); i++) {
                JSONObject resource = linked.optJSONObject(i);
                if (resource == null) continue;
                enrichText(resource, resource.optString("content"));
                JSONObject summary = resource.optJSONObject("apiSummary");
                if (summary != null) {
                    summaries.put(new JSONObject().put("url", resource.optString("url")).put("summary", summary));
                    JSONArray found = summary.optJSONArray("operations");
                    if (found != null) for (int j = 0; j < found.length(); j++) operations.put(found.get(j));
                }
            }
        }
        if (summaries.length() > 0) source.put("discoveredApiContracts", summaries);
        if (operations.length() > 0) source.put("discoveredOperations", operations);
        return source;
    }

    private void enrichText(JSONObject source, String text) {
        if (text == null || text.isBlank()) return;
        try {
            JSONObject document = new JSONObject(text);
            if (document.has("openapi") || document.has("swagger")) {
                source.put("sourceType", "openApi");
                source.put("apiSummary", summarizeOpenApi(document, source.optString("url")));
            } else if (document.has("info") && document.has("item")) {
                source.put("sourceType", "postmanCollection");
                source.put("apiSummary", summarizePostman(document));
            }
        } catch (Exception ignored) {
            String lower = text.toLowerCase();
            if (lower.contains("openapi:") || lower.contains("swagger:")) source.put("sourceType", "openApi");
        }
    }

    private JSONObject summarizeOpenApi(JSONObject root, String documentUrl) {
        JSONArray operations = new JSONArray();
        JSONObject paths = root.optJSONObject("paths");
        if (paths != null) {
            for (Iterator<String> pathKeys = paths.keys(); pathKeys.hasNext(); ) {
                String path = pathKeys.next();
                JSONObject methods = paths.optJSONObject(path);
                if (methods == null) continue;
                for (Iterator<String> methodKeys = methods.keys(); methodKeys.hasNext(); ) {
                    String method = methodKeys.next();
                    if (!method.matches("(?i)get|post|put|patch|delete|head|options|trace")) continue;
                    JSONObject operation = methods.optJSONObject(method);
                    JSONArray baseUris = resolveServers(operation == null ? null : operation.optJSONArray("servers"),
                            root.optJSONArray("servers"), documentUrl);
                    operations.put(new JSONObject()
                            .put("method", method.toUpperCase())
                            .put("path", path)
                            .put("baseUri", baseUris.isEmpty() ? inferOrigin(documentUrl) : baseUris.optString(0))
                            .put("baseUris", baseUris)
                            .put("requestPayload", summarizeRequestPayload(operation))
                            .put("expectedReturnCodes", responseCodes(operation))
                            .put("operationId", operation == null ? "" : operation.optString("operationId"))
                            .put("summary", operation == null ? "" : operation.optString("summary")));
                }
            }
        }
        return new JSONObject()
                .put("title", root.optJSONObject("info") == null ? "" : root.getJSONObject("info").optString("title"))
                .put("version", root.optJSONObject("info") == null ? "" : root.getJSONObject("info").optString("version"))
                .put("operations", operations);
    }

    private JSONArray resolveServers(JSONArray operationServers, JSONArray rootServers, String documentUrl) {
        JSONArray selected = operationServers != null && !operationServers.isEmpty() ? operationServers : rootServers;
        JSONArray result = new JSONArray();
        if (selected == null || selected.isEmpty()) {
            String inferred = inferOrigin(documentUrl);
            if (!inferred.isBlank()) result.put(inferred);
            return result;
        }
        for (int i = 0; i < selected.length(); i++) {
            JSONObject server = selected.optJSONObject(i);
            String value = server == null ? "" : server.optString("url");
            JSONObject variables = server == null ? null : server.optJSONObject("variables");
            if (variables != null) {
                for (Iterator<String> keys = variables.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject variable = variables.optJSONObject(key);
                    value = value.replace("{" + key + "}", variable == null ? "" : variable.optString("default"));
                }
            }
            try {
                if (!value.isBlank() && documentUrl != null && !documentUrl.isBlank()) value = URI.create(documentUrl).resolve(value).toString();
            } catch (Exception ignored) { }
            if (!value.isBlank()) result.put(value);
        }
        return result;
    }

    private String inferOrigin(String documentUrl) {
        try {
            URI uri = URI.create(documentUrl);
            if (uri.getHost() == null) return "";
            return new URI(uri.getScheme(), uri.getAuthority(), "/", null, null).toString();
        } catch (Exception ignored) { return ""; }
    }

    private Object summarizeRequestPayload(JSONObject operation) {
        JSONObject requestBody = operation == null ? null : operation.optJSONObject("requestBody");
        if (requestBody == null) return "";
        JSONObject summary = new JSONObject().put("required", requestBody.optBoolean("required"));
        JSONObject content = requestBody.optJSONObject("content");
        if (content == null) return summary;
        JSONObject media = new JSONObject();
        for (Iterator<String> types = content.keys(); types.hasNext(); ) {
            String type = types.next();
            JSONObject definition = content.optJSONObject(type);
            if (definition == null) continue;
            JSONObject details = new JSONObject();
            if (definition.has("example")) details.put("example", definition.opt("example"));
            if (definition.has("examples")) details.put("examples", definition.opt("examples"));
            if (definition.has("schema")) details.put("schema", definition.opt("schema"));
            media.put(type, details);
        }
        return summary.put("content", media);
    }

    private JSONArray responseCodes(JSONObject operation) {
        JSONArray codes = new JSONArray();
        JSONObject responses = operation == null ? null : operation.optJSONObject("responses");
        if (responses != null) for (Iterator<String> keys = responses.keys(); keys.hasNext(); ) codes.put(keys.next());
        return codes;
    }

    private JSONObject summarizePostman(JSONObject root) {
        JSONArray requests = new JSONArray();
        collectPostmanItems(root.optJSONArray("item"), "", requests);
        return new JSONObject()
                .put("title", root.optJSONObject("info") == null ? "" : root.getJSONObject("info").optString("name"))
                .put("requests", requests);
    }

    private void collectPostmanItems(JSONArray items, String folder, JSONArray requests) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name");
            if (item.optJSONArray("item") != null) {
                collectPostmanItems(item.getJSONArray("item"), folder.isBlank() ? name : folder + "/" + name, requests);
                continue;
            }
            JSONObject request = item.optJSONObject("request");
            if (request == null) continue;
            Object url = request.opt("url");
            requests.put(new JSONObject()
                    .put("folder", folder)
                    .put("name", name)
                    .put("method", request.optString("method"))
                    .put("url", url == null ? "" : url.toString()));
        }
    }
}
