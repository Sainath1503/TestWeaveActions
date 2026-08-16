import service.TestSuiteHtmlReportRenderer;

import java.util.List;

public final class TestSuiteHtmlReportRendererSmokeTest {
    public static void main(String[] args) {
        List<TestSuiteHtmlReportRenderer.Step> steps = List.of(
                new TestSuiteHtmlReportRenderer.Step("Suite A", "Case A", "Passing step", "Field Validation",
                        "Passed", true, List.of(new TestSuiteHtmlReportRenderer.Validation(
                        "$.name", "Type: string", "Veyra", "Veyra", true, ""))),
                new TestSuiteHtmlReportRenderer.Step("Suite A", "Case A", "Failing <step>", "JSON Compare",
                        "Failed", false, List.of(new TestSuiteHtmlReportRenderer.Validation(
                        "$.id", "JSON Match", "123", "456", false, "JSON comparison mismatch"))));

        String html = TestSuiteHtmlReportRenderer.render("GitHub Actions", steps);
        require(html.contains("<title>VeyraAI Test Suite Report</title>"), "shared report title is missing");
        require(html.contains("Execution Tree"), "execution tree is missing");
        require(html.contains("Status Split") && html.contains("Run Health"), "summary visualizations are missing");
        require(html.contains("data-step=\"step-1\""), "step navigation is missing");
        require(html.contains("Failure Error Message</b><br>JSON comparison mismatch"),
                "failed validation message is not used in the failure banner");
        require(html.contains("Failing &lt;step&gt;"), "report values are not HTML escaped");
        require(!html.contains("Failing <step>"), "unescaped report value leaked into HTML");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
