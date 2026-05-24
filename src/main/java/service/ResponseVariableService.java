package service;

import model.ResponseFieldCandidate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

public class ResponseVariableService {

    private static final int MAX_PREVIEW_LENGTH = 140;

    public List<ResponseFieldCandidate> parseFields(String responseBody) {
        return parseFields(responseBody, false);
    }

    public List<ResponseFieldCandidate> parseAllFields(String responseBody) {
        return parseFields(responseBody, true);
    }

    private List<ResponseFieldCandidate> parseFields(String responseBody, boolean includeContainers) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }

        Object root = new JSONTokener(responseBody).nextValue();
        ConcurrentLinkedQueue<ResponseFieldCandidate> candidates = new ConcurrentLinkedQueue<>();
        ForkJoinPool pool = ForkJoinPool.commonPool();
        pool.invoke(new ParseTask(root, "$", "", candidates, includeContainers));

        List<ResponseFieldCandidate> fields = new ArrayList<>(candidates);
        fields.sort(Comparator.comparing(field -> field.jsonPath));
        return fields;
    }

    private static class ParseTask extends RecursiveAction {
        private final Object value;
        private final String path;
        private final String fieldName;
        private final ConcurrentLinkedQueue<ResponseFieldCandidate> candidates;
        private final boolean includeContainers;

        private ParseTask(Object value, String path, String fieldName,
                          ConcurrentLinkedQueue<ResponseFieldCandidate> candidates,
                          boolean includeContainers) {
            this.value = value;
            this.path = path;
            this.fieldName = fieldName;
            this.candidates = candidates;
            this.includeContainers = includeContainers;
        }

        @Override
        protected void compute() {
            if (value instanceof JSONObject object) {
                if (includeContainers) {
                    candidates.add(new ResponseFieldCandidate(path, fieldName, String.valueOf(object), preview(object), typeOf(object)));
                }
                List<ParseTask> tasks = new ArrayList<>();
                for (String key : object.keySet()) {
                    Object child = object.get(key);
                    tasks.add(new ParseTask(child, path + "." + key, key, candidates, includeContainers));
                }
                invokeAll(tasks);
                return;
            }

            if (value instanceof JSONArray array) {
                if (includeContainers) {
                    candidates.add(new ResponseFieldCandidate(path, fieldName, String.valueOf(array), preview(array), typeOf(array)));
                }
                List<ParseTask> tasks = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    Object child = array.get(i);
                    tasks.add(new ParseTask(child, path + "[" + i + "]", fieldName, candidates, includeContainers));
                }
                invokeAll(tasks);
                return;
            }

            String fullValue = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
            candidates.add(new ResponseFieldCandidate(path, fieldName, fullValue, preview(value), typeOf(value)));
        }
    }

    private static String preview(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_PREVIEW_LENGTH ? text : text.substring(0, MAX_PREVIEW_LENGTH - 3) + "...";
    }

    private static String typeOf(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof Number) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return "integer";
            }
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof JSONObject) {
            return "object";
        }
        if (value instanceof JSONArray) {
            return "array";
        }
        return "string";
    }
}
