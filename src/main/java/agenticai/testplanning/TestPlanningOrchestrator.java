package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** End-to-end workflow shared by the JavaFX tab and smoke tests. */
public final class TestPlanningOrchestrator {
    private final KnowledgeSourceMcpServer sources;
    private final TestPlanningAgent agent;
    private final TestWorkbookMcpServer workbooks;

    public TestPlanningOrchestrator(KnowledgeSourceMcpServer sources, TestPlanningAgent agent,
                                    TestWorkbookMcpServer workbooks) {
        this.sources = sources;
        this.agent = agent;
        this.workbooks = workbooks;
    }

    public RunResult run(List<String> sourceLinks, List<Path> uploads, Path cacheRoot, Path outputPath,
                         JSONArray relevantMemory, String userInstructions, TestPlanningAgent.ModelInvoker model,
                         Consumer<String> log) throws Exception {
        if (log != null) log.accept("Classifying and extracting knowledge sources...");
        JSONObject context = sources.buildContext(sourceLinks, uploads, cacheRoot, log);
        if (context.optInt("sourceCount") == 0) {
            throw new IllegalArgumentException("No readable knowledge source was found. Review the source errors and try again.");
        }
        if (log != null) log.accept("Analysing requirements and generating the test plan...");
        JSONObject result = agent.analyze(context, relevantMemory, userInstructions, model);
        result.put("sourceSummary", context);
        if (log != null) log.accept("Publishing the Excel workbook...");
        Path workbook = workbooks.write(result, outputPath);
        if (log != null) log.accept("Workbook generated: " + workbook);
        return new RunResult(context, result, workbook);
    }

    public record RunResult(JSONObject sourceContext, JSONObject result, Path workbookPath) {
    }
}
