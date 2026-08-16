package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Declarative skill catalog executed in order by the single Test Planning Agent. */
public final class TestPlanningSkillCatalog {
    private final Map<String, String> skills = new LinkedHashMap<>();

    public TestPlanningSkillCatalog() {
        skills.put("test-plan-orchestrator", "Coordinate intake, analysis, quality review, publishing, and memory.");
        skills.put("knowledge-source-intake", "Classify, deduplicate, validate, and preserve provenance for every source.");
        skills.put("requirements-synthesis", "Extract requirements, business rules, journeys, constraints, and assumptions.");
        skills.put("project-code-analysis", "Identify modules, routes, models, validations, integrations, and existing tests.");
        skills.put("api-contract-analysis", "Analyse OpenAPI, Swagger, and Postman operations, schemas, auth, and constraints.");
        skills.put("risk-based-test-strategy", "Prioritize scope, levels, test types, environments, risks, and exit criteria.");
        skills.put("test-case-generation", "Generate observable positive, negative, boundary, integration, and regression cases.");
        skills.put("test-data-generation", "Generate linked synthetic valid, invalid, boundary, setup, and cleanup data.");
        skills.put("coverage-and-quality-review", "Detect gaps, duplicates, contradictions, invalid links, and weak expectations.");
        skills.put("test-plan-workbook-publisher", "Publish strategy, cases, data, and traceability as separate workbook sheets.");
        skills.put("test-knowledge-memory", "Retrieve and save project-scoped, reusable, source-grounded knowledge.");
    }

    public JSONArray listSkills() {
        JSONArray result = new JSONArray();
        skills.forEach((id, instruction) -> result.put(
                new JSONObject().put("id", id).put("instruction", instruction)));
        return result;
    }

    public String promptGuide() {
        StringBuilder guide = new StringBuilder();
        skills.forEach((id, instruction) -> guide.append("- ").append(id).append(": ")
                .append(instruction).append('\n'));
        return guide.toString();
    }
}
