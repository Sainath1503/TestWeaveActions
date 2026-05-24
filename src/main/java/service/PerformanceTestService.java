package service;

import model.ApiRequest;
import model.PerformanceTestResult;
import org.apache.http.entity.ContentType;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.listeners.HtmlReporter;
import us.abstracta.jmeter.javadsl.core.listeners.JtlWriter;
import us.abstracta.jmeter.javadsl.core.stats.StatsSummary;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static us.abstracta.jmeter.javadsl.JmeterDsl.htmlReporter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

public class PerformanceTestService {

    private static final String RANDOM_STRING_PLACEHOLDER = "{$randomstring}";
    private static final String RANDOM_INT_PLACEHOLDER = "{$randomint}";
    private static final String JMETER_RANDOM_STRING_FUNCTION =
            "${__RandomString(12,abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789,)}";
    private static final String JMETER_RANDOM_INT_FUNCTION =
            "${__Random(10000,999999,)}";
    private static final DateTimeFormatter REPORT_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public PerformanceTestResult runLoadTest(ApiRequest request, int threads, int iterationsPerThread) throws Exception {
        validate(request, threads, iterationsPerThread);

        String method = request.method.trim().toUpperCase();
        String reportName = "load-test-" + REPORT_NAME_FORMAT.format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportsRoot = Path.of("target", "performance-reports");
        Files.createDirectories(reportsRoot);

        Path reportDirectory = reportsRoot.resolve(reportName);
        Path jtlPath = reportDirectory.resolve("request-capture.xml");
        boolean captureRequests = threads < 3 && iterationsPerThread < 5;

        DslHttpSampler sampler = buildSampler(request, method);
        HtmlReporter reporter = htmlReporter(reportsRoot.toString(), reportName)
                .timeGraphsGranularity(Duration.ofSeconds(5));

        TestPlanStats stats;
        if (captureRequests) {
            JtlWriter requestWriter = jtlWriter(jtlPath.toString())
                    .saveAsXml(true)
                    .withFieldNames(true)
                    .withLabel(true)
                    .withThreadName(true)
                    .withTimeStamp(true)
                    .withElapsedTime(true)
                    .withResponseCode(true)
                    .withSuccess(true)
                    .withUrl(true)
                    .withRequestHeaders(true)
                    .withSamplerData(true);

            stats = testPlan(
                    threadGroup("API Load Test", threads, iterationsPerThread, sampler),
                    reporter,
                    requestWriter
            ).run();
        } else {
            stats = testPlan(
                    threadGroup("API Load Test", threads, iterationsPerThread, sampler),
                    reporter
            ).run();
        }

        StatsSummary overall = stats.overall();
        PerformanceTestResult result = new PerformanceTestResult();
        result.method = method;
        result.endpoint = request.url;
        result.threads = threads;
        result.iterationsPerThread = iterationsPerThread;
        result.samples = overall.samplesCount();
        result.errors = overall.errorsCount();
        result.errorPercent = result.samples == 0 ? 0 : (result.errors * 100.0) / result.samples;
        result.throughputPerSecond = overall.samples().perSecond();
        result.duration = stats.duration();
        result.min = overall.sampleTime().min();
        result.mean = overall.sampleTime().mean();
        result.median = overall.sampleTime().median();
        result.perc90 = overall.sampleTime().perc90();
        result.perc95 = overall.sampleTime().perc95();
        result.perc99 = overall.sampleTime().perc99();
        result.max = overall.sampleTime().max();
        result.reportDirectory = reportDirectory;
        result.reportIndexPath = reportDirectory.resolve("index.html");
        result.chartValuesMs.put("Avg", (double) result.mean.toMillis());
        result.chartValuesMs.put("P90", (double) result.perc90.toMillis());
        result.chartValuesMs.put("P95", (double) result.perc95.toMillis());
        result.chartValuesMs.put("P99", (double) result.perc99.toMillis());

        if (captureRequests && Files.exists(jtlPath)) {
            try {
                Path requestJsonPath = reportDirectory.resolve("api-requests.json");
                buildRequestJsonFile(jtlPath, requestJsonPath, request);
                result.requestCaptureJsonPath = requestJsonPath;
            } catch (Exception ignored) {
                result.requestCaptureJsonPath = null;
            }
        }

        return result;
    }

    private DslHttpSampler buildSampler(ApiRequest request, String method) {
        DslHttpSampler sampler = httpSampler("API Load Request", request.url)
                .method(method)
                .connectionTimeout(Duration.ofSeconds(15))
                .responseTimeout(Duration.ofSeconds(30))
                .header("User-Agent", "API-Validator-Tool/1.0");

        if (request.headers != null) {
            for (Map.Entry<String, String> entry : request.headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getKey().isBlank() && !entry.getValue().isBlank()) {
                    sampler.header(entry.getKey(), entry.getValue());
                }
            }
        }

        if (request.token != null && !request.token.isBlank()) {
            sampler.header("Authorization", "Bearer " + request.token);
        }

        if (requiresBody(method) && request.body != null && !request.body.isBlank()) {
            sampler.body(applyRuntimePlaceholders(request.body));
            if (!hasContentType(request.headers)) {
                sampler.contentType(ContentType.APPLICATION_JSON);
            }
        }

        return sampler;
    }

    private void buildRequestJsonFile(Path jtlPath, Path jsonPath, ApiRequest request) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(jtlPath.toFile());
        document.getDocumentElement().normalize();

        JSONArray requests = new JSONArray();
        NodeList samples = document.getDocumentElement().getChildNodes();
        int hitIndex = 0;
        for (int i = 0; i < samples.getLength(); i++) {
            if (!(samples.item(i) instanceof Element sample)) {
                continue;
            }
            if (!"httpSample".equals(sample.getTagName()) && !"sample".equals(sample.getTagName())) {
                continue;
            }

            hitIndex++;
            JSONObject entry = new JSONObject();
            entry.put("hit", hitIndex);
            entry.put("thread", sample.getAttribute("tn"));
            entry.put("timestamp", sample.getAttribute("ts"));
            entry.put("method", request.method);
            entry.put("url", textOrAttribute(sample, "java.net.URL", "url"));
            entry.put("requestHeaders", childText(sample, "requestHeader"));
            entry.put("requestPayload", childText(sample, "samplerData"));
            requests.put(entry);
        }

        JSONObject root = new JSONObject();
        root.put("endpoint", request.url);
        root.put("method", request.method);
        root.put("capturedRequests", requests);
        Files.writeString(jsonPath, root.toString(2), StandardCharsets.UTF_8);
    }

    private String childText(Element element, String tagName) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList.getLength() == 0) {
            return "";
        }
        return nodeList.item(0).getTextContent();
    }

    private String textOrAttribute(Element element, String tagName, String attributeName) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return element.getAttribute(attributeName);
    }

    private boolean hasContentType(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        for (String key : headers.keySet()) {
            if ("Content-Type".equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private String applyRuntimePlaceholders(String body) {
        return body
                .replace(RANDOM_STRING_PLACEHOLDER, JMETER_RANDOM_STRING_FUNCTION)
                .replace(RANDOM_INT_PLACEHOLDER, JMETER_RANDOM_INT_FUNCTION);
    }

    private void validate(ApiRequest request, int threads, int iterationsPerThread) {
        if (request == null || request.url == null || request.url.isBlank()) {
            throw new IllegalArgumentException("Endpoint is required before running a performance test.");
        }
        if (request.method == null || request.method.isBlank()) {
            throw new IllegalArgumentException("HTTP method is required before running a performance test.");
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("Threads must be greater than 0.");
        }
        if (iterationsPerThread <= 0) {
            throw new IllegalArgumentException("Iterations must be greater than 0.");
        }
    }
}
