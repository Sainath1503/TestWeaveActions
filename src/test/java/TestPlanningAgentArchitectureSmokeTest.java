import agenticai.AgentRegistry;
import agenticai.AgentSessionManager;
import agenticai.AgenticAIConnectionManager;
import agenticai.testplanning.ApiContractMcpServer;
import agenticai.testplanning.DocumentConversionMcpServer;
import agenticai.testplanning.JiraMcpServer;
import agenticai.testplanning.KnowledgeSourceMcpServer;
import agenticai.testplanning.ProjectRepositoryMcpServer;
import agenticai.testplanning.TestPlanningAgent;
import agenticai.testplanning.TestPlanningMemoryMcpServer;
import agenticai.testplanning.TestPlanningOrchestrator;
import agenticai.testplanning.TestPlanningPromptBuilder;
import agenticai.testplanning.TestPlanningResponseValidator;
import agenticai.testplanning.TestPlanningSkillCatalog;
import agenticai.testplanning.TestWorkbookMcpServer;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public final class TestPlanningAgentArchitectureSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("veyra-test-planning-smoke-");
        Path csv = root.resolve("requirements.csv");
        Files.writeString(csv, "Requirement,Expected\nUser can log in,Dashboard is displayed\n", StandardCharsets.UTF_8);
        Path postman = root.resolve("collection.json");
        Files.writeString(postman, new JSONObject()
                .put("info", new JSONObject().put("name", "Login API"))
                .put("item", new JSONArray().put(new JSONObject().put("name", "Login")
                        .put("request", new JSONObject().put("method", "POST")
                                .put("url", "https://example.test/login")))).toString(), StandardCharsets.UTF_8);

        KnowledgeSourceMcpServer sources = new KnowledgeSourceMcpServer(
                new DocumentConversionMcpServer(), new ProjectRepositoryMcpServer(),
                new ApiContractMcpServer(), new JiraMcpServer());
        verifySwaggerPageCrawling(sources, root);
        TestPlanningAgent agent = new TestPlanningAgent(
                new TestPlanningPromptBuilder(), new TestPlanningResponseValidator());
        verifyApiCoverageRetry(agent);
        TestPlanningOrchestrator orchestrator = new TestPlanningOrchestrator(
                sources, agent, new TestWorkbookMcpServer());
        Path workbook = root.resolve("generated.xlsx");
        TestPlanningOrchestrator.RunResult run = orchestrator.run(
                List.of(), List.of(csv, postman), root.resolve("cache"), workbook,
                new JSONArray(), "Generate a login test plan.", prompt -> {
                    require(prompt.contains("SRC-1") && prompt.contains("User can log in"),
                            "extracted source context was not sent to the model");
                    require(prompt.contains("Login API") && prompt.contains("POST"),
                            "Postman collection was not normalized");
                    return modelResult().toString();
                }, ignored -> {});

        require(Files.isRegularFile(run.workbookPath()), "workbook was not generated");
        try (var input = Files.newInputStream(workbook); var book = WorkbookFactory.create(input)) {
            require(book.getNumberOfSheets() == 4, "workbook must contain four sheets");
            require(book.getSheet("Test Strategy") != null, "Test Strategy sheet missing");
            require(book.getSheet("Test Cases") != null, "Test Cases sheet missing");
            require(book.getSheet("Test Data") != null, "Test Data sheet missing");
            require(book.getSheet("Traceability") != null, "Traceability sheet missing");
            var header = book.getSheet("Test Cases").getRow(0);
            require("Base URI".equals(header.getCell(6).getStringCellValue()), "Base URI column missing");
            require("Endpoint".equals(header.getCell(7).getStringCellValue()), "Endpoint column missing");
            require("Request Payload".equals(header.getCell(9).getStringCellValue()), "Request Payload column missing");
            require("Expected Return Code".equals(header.getCell(10).getStringCellValue()), "return-code column missing");
        }

        JSONArray selected = new TestPlanningMemoryMcpServer().selectRelevant(
                new JSONArray().put(new JSONObject().put("summary", "login authentication rule"))
                        .put(new JSONObject().put("summary", "unrelated checkout rule")),
                "login project", 5);
        require(selected.length() == 1, "memory retrieval did not filter similar context");
        require(new TestPlanningSkillCatalog().listSkills().length() == 11,
                "the Test Planning Agent must expose all eleven focused skills");

        AgentRegistry registry = new AgentRegistry();
        registry.register("apiAgent", ignored -> {});
        registry.register("dbAgent", ignored -> {});
        registry.register("webAgent", ignored -> {});
        registry.register("testPlanningAgent", ignored -> {});
        AgenticAIConnectionManager.ConnectionResult connection =
                new AgenticAIConnectionManager(new AgentSessionManager(), registry)
                        .connectNewSession("test", "test-planning-session");
        require(connection.fullyConnected()
                        && connection.session().connectedAgents().contains("testPlanningAgent"),
                "Test Planning Agent did not join the shared Agentic AI session");

        String ui = Files.readString(Path.of("src/main/java/ui/ApiValidatorFxApp.java"));
        require(ui.contains("tab(\"Test Planning Agent\", createTestPlanningAgentPanel())"),
                "Test Planning Agent sub-tab is not registered");
        require(ui.contains("Agentic AI - API, DB, Web & Test Planning Agents"),
                "Settings does not mention the Test Planning Agent");
        require(ui.contains("runTestPlanningAgentSkill"),
                "testDesign routes remain unavailable");
        System.out.println("FEATURE_TEST_PASS Test Planning Agent ingestion, routing, memory and workbook generation");
    }

    private static void verifyApiCoverageRetry(TestPlanningAgent agent) throws Exception {
        JSONObject source = new JSONObject().put("sourceId", "SRC-1")
                .put("discoveredOperations", new JSONArray().put(
                        new JSONObject().put("method", "GET").put("path", "/meals")));
        JSONObject context = new JSONObject().put("sources", new JSONArray().put(source));
        AtomicInteger calls = new AtomicInteger();
        JSONObject result = agent.analyze(context, new JSONArray(), "Cover the API", prompt -> {
            JSONObject response = modelResult();
            if (calls.incrementAndGet() > 1) {
                response.getJSONArray("testCases").getJSONObject(0)
                        .put("scenario", "GET /meals returns the meal collection")
                        .put("baseUri", "https://example.test/").put("endpoint", "/meals")
                        .put("httpMethod", "GET").put("requestPayload", "N/A")
                        .put("expectedReturnCode", "200");
                response.getJSONArray("traceability").getJSONObject(0)
                        .put("requirement", "GET /meals operation");
            }
            return response.toString();
        });
        require(calls.get() == 2 && result.toString().contains("GET /meals"),
                "agent did not retry an incomplete endpoint plan with explicit missing coverage");
    }

    private static void verifySwaggerPageCrawling(KnowledgeSourceMcpServer sources, Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api-docs", exchange -> {
            byte[] body = "<html><script>SwaggerUIBundle({url: '/openapi.json'})</script><div>API docs</div></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/openapi.json", exchange -> {
            byte[] body = new JSONObject().put("openapi", "3.0.0")
                    .put("info", new JSONObject().put("title", "Food API").put("version", "1"))
                    .put("paths", new JSONObject()
                            .put("/meals", new JSONObject().put("get", new JSONObject().put("summary", "List meals")))
                            .put("/orders/{id}", new JSONObject().put("patch", new JSONObject().put("summary", "Update order"))))
                    .toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api-docs";
            JSONObject context = sources.buildContext(
                    List.of(url + " | \"Crawl this page and test every documented endpoint\""), List.of(), root, ignored -> {});
            require(context.optInt("sourceCount") == 1, "URL plus instructions was not separated correctly");
            JSONObject source = context.getJSONArray("sources").getJSONObject(0);
            require(source.optString("sourceInstructions").contains("every documented endpoint"),
                    "source-specific instructions were not preserved");
            JSONArray operations = source.optJSONArray("discoveredOperations");
            require(operations != null && operations.length() == 2,
                    "Swagger UI page did not crawl and normalize its OpenAPI operations");
            require(operations.toString().contains("GET") && operations.toString().contains("PATCH"),
                    "HTTP methods from the linked OpenAPI contract were not retained");
            JSONObject first = operations.getJSONObject(0);
            require(first.optString("baseUri").startsWith("http://127.0.0.1:"), "Base URI was not resolved");
            require(first.has("requestPayload") && first.has("expectedReturnCodes"),
                    "request payload and expected status metadata were not normalized");
        } finally {
            server.stop(0);
        }
    }

    private static JSONObject modelResult() {
        return new JSONObject()
                .put("projectName", "Login Project")
                .put("analysisSummary", "Login coverage generated")
                .put("testStrategy", new JSONArray().put(new JSONObject()
                        .put("section", "Scope").put("item", "Authentication")
                        .put("details", "Validate login").put("priority", "High")
                        .put("sourceReferences", new JSONArray().put("SRC-1"))))
                .put("testCases", new JSONArray().put(new JSONObject()
                        .put("testCaseId", "TC-001").put("requirementId", "REQ-001")
                        .put("module", "Authentication").put("scenario", "Valid login")
                        .put("baseUri", "N/A").put("endpoint", "N/A").put("httpMethod", "N/A")
                        .put("requestPayload", "N/A").put("expectedReturnCode", "N/A")
                        .put("preconditions", new JSONArray().put("User exists"))
                        .put("steps", new JSONArray().put("Submit valid credentials"))
                        .put("expectedResult", "Dashboard is displayed").put("testType", "Functional")
                        .put("priority", "High").put("classification", "Positive")
                        .put("automationCandidate", "Yes").put("testDataIds", new JSONArray().put("TD-001"))
                        .put("sourceReferences", new JSONArray().put("SRC-1"))))
                .put("testData", new JSONArray().put(new JSONObject()
                        .put("testDataId", "TD-001").put("testCaseIds", new JSONArray().put("TC-001"))
                        .put("field", "username").put("validValue", "synthetic-user")
                        .put("invalidValue", "").put("boundaryValue", "").put("setup", "Create user")
                        .put("cleanup", "Delete user").put("sensitivity", "Internal").put("notes", "")))
                .put("traceability", new JSONArray().put(new JSONObject()
                        .put("requirementId", "REQ-001").put("requirement", "User can log in")
                        .put("sourceReference", "SRC-1").put("testCaseIds", new JSONArray().put("TC-001"))
                        .put("coverageStatus", "Covered").put("notes", "")))
                .put("memory", new JSONArray().put(new JSONObject()
                        .put("type", "requirement").put("summary", "User login is required")
                        .put("content", new JSONObject().put("fact", "Valid users reach the dashboard"))
                        .put("tags", new JSONArray().put("login")).put("confidence", 0.95)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
