package service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared HTML renderer for desktop and CI test-suite executions.
 * Keeping the markup here prevents the local and GitHub Actions reports from
 * drifting into different layouts and result representations.
 */
public final class TestSuiteHtmlReportRenderer {

    private TestSuiteHtmlReportRenderer() {
    }

    public record Validation(String field, String validation, String expected, String actual,
                             boolean passed, String message) {
        public Validation {
            field = safe(field);
            validation = safe(validation);
            expected = safe(expected);
            actual = safe(actual);
            message = safe(message);
        }
    }

    public record Step(String suite, String testCase, String name, String type, String status,
                       boolean passed, List<Validation> validations) {
        public Step {
            suite = fallback(suite, "Untitled Suite");
            testCase = fallback(testCase, "Untitled Test Case");
            name = fallback(name, "Untitled Step");
            type = safe(type);
            status = safe(status);
            validations = validations == null ? List.of() : List.copyOf(validations);
        }
    }

    public static String render(String source, List<Step> suppliedSteps) {
        List<Step> steps = suppliedSteps == null ? List.of() : List.copyOf(suppliedSteps);
        long passed = steps.stream().filter(Step::passed).count();
        long failed = steps.size() - passed;
        long total = steps.size();
        int passPercent = total == 0 ? 0 : Math.round((passed * 100f) / total);
        int failPercent = total == 0 ? 0 : 100 - passPercent;
        Map<String, Map<String, List<Integer>>> tree = executionTree(steps);

        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>VeyraAI Test Suite Report</title>
                  <style>
                    :root{--blue:#2f7cff;--cyan:#18d8e8;--violet:#8b4df6;--ink:#f8fbff;--muted:#b7c7f7;--line:#243b78;--bg:#05081a;--panel:#111b3d;--panel2:#0d1430;--paper:#f8fbff;--paper-ink:#111827;--pass:#20d38f;--fail:#ff4f72}
                    *{box-sizing:border-box}
                    body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:var(--bg);color:var(--ink)}
                    header{background:linear-gradient(110deg,#06091f,#111b3d,#271052);color:white;padding:22px 30px;border-bottom:1px solid var(--line)}
                    header h1{margin:0 0 6px;font-size:26px}
                    header div{opacity:.9;font-size:13px;word-break:break-all}
                    main{display:grid;grid-template-columns:310px minmax(0,1fr);gap:18px;padding:18px 24px 28px}
                    aside{background:var(--panel);border:1px solid var(--line);border-radius:8px;min-height:calc(100vh - 130px);padding:14px;position:sticky;top:14px;align-self:start}
                    aside h2{margin:0 0 12px;color:var(--cyan);font-size:18px}
                    details{border-top:1px solid var(--line);padding:8px 0}
                    summary{cursor:pointer;font-weight:700;color:#ffffff}
                    .case summary{font-weight:600;color:var(--muted);margin-left:10px}
                    .step-link{display:flex;align-items:center;gap:8px;width:calc(100% - 22px);margin:6px 0 4px 22px;padding:8px 9px;border:1px solid #2a4080;border-radius:6px;background:#0d1430;color:#f8fbff;text-align:left;cursor:pointer;font:13px Segoe UI,Arial,sans-serif}
                    .step-link:hover,.step-link.active{border-color:var(--cyan);background:#17265a}
                    .dot{width:9px;height:9px;border-radius:50%;display:inline-block;flex:0 0 auto}.dot.pass{background:var(--pass)}.dot.fail{background:var(--fail)}
                    .summary-cards{display:grid;grid-template-columns:repeat(4,minmax(140px,1fr));gap:12px;margin-bottom:14px}
                    .metric{background:var(--panel);border:1px solid var(--line);padding:14px;border-radius:8px}.metric b{display:block;font-size:28px;line-height:1.1}
                    .viz{display:grid;grid-template-columns:260px minmax(0,1fr);gap:14px;margin-bottom:14px}
                    .card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:16px}
                    .pie{width:164px;height:164px;border-radius:50%;margin:8px auto;background:conic-gradient(var(--pass) 0 var(--pass-pct),var(--fail) var(--pass-pct) 100%)}
                    .bar{height:28px;display:flex;border-radius:5px;overflow:hidden;background:#18234b;margin:20px 0 10px}.passbar{background:var(--pass)}.failbar{background:var(--fail)}
                    .legend{color:var(--muted);font-size:14px}
                    .detail{display:none;background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:18px}.detail.active{display:block}
                    .detail-head{display:flex;justify-content:space-between;gap:16px;border-bottom:1px solid var(--line);padding-bottom:12px;margin-bottom:14px}
                    h2{margin:0;color:var(--cyan);font-size:22px}.meta{color:var(--muted);font-size:14px;margin-top:6px}
                    .pill{border-radius:999px;padding:6px 10px;font-weight:700;align-self:start}.pill.pass{background:#d9fff6;color:#07533d}.pill.fail{background:#ffe3eb;color:#8f1832}
                    .facts{display:grid;grid-template-columns:repeat(4,minmax(120px,1fr));gap:10px;margin-bottom:14px}
                    .fact{background:var(--paper);border:1px solid #b7cff6;border-radius:6px;padding:10px;color:var(--paper-ink)}.fact span{display:block;color:#526480;font-size:12px}.fact b{font-size:15px}
                    .error{background:#ffeef3;border:1px solid #ff9ab0;color:#8f1832;border-radius:6px;padding:12px;margin:12px 0}
                    table{width:100%;border-collapse:collapse;font-size:14px}th,td{border:1px solid #d5e6ff;padding:8px;vertical-align:top;text-align:left;color:var(--paper-ink)}
                    th{background:#101936;color:#ffffff}.ok{color:#067a52;font-weight:700}.bad{color:#b11d3b;font-weight:700}td{background:var(--paper)}
                    pre{white-space:pre-wrap;word-break:break-word;margin:0;font-family:Consolas,monospace;font-size:13px}
                    .empty{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:18px;color:var(--muted)}
                    @media(max-width:960px){main{grid-template-columns:1fr}aside{position:static;min-height:auto}.summary-cards,.viz,.facts{grid-template-columns:1fr}}
                  </style>
                </head>
                <body>
                """);
        html.append("<header><h1>Test Suite Run Report</h1><div>")
                .append(escape(source)).append("</div></header><main>");

        appendTree(html, tree, steps);
        html.append("<section><div class=\"summary-cards\">")
                .append(metric("Total Steps", String.valueOf(total)))
                .append(metric("Passed", String.valueOf(passed)))
                .append(metric("Failed", String.valueOf(failed)))
                .append(metric("Pass Rate", passPercent + "%"))
                .append("</div>");
        html.append("<div class=\"viz\"><div class=\"card\"><h2>Status Split</h2><div class=\"pie\" style=\"--pass-pct:")
                .append(passPercent).append("%\"></div><div class=\"legend\">Passed: ")
                .append(passed).append(" | Failed: ").append(failed)
                .append("</div></div><div class=\"card\"><h2>Run Health</h2><div class=\"bar\">")
                .append("<div class=\"passbar\" style=\"width:").append(passPercent).append("%\"></div>")
                .append("<div class=\"failbar\" style=\"width:").append(failPercent).append("%\"></div>")
                .append("</div><div class=\"legend\">Use the execution tree to inspect every test step, validations, expected values, actual values, and error messages.</div></div></div>");

        if (steps.isEmpty()) {
            html.append("<div class=\"empty\">No test steps were produced for this execution.</div>");
        } else {
            for (int i = 0; i < steps.size(); i++) {
                appendStep(html, steps.get(i), i);
            }
        }
        html.append("</section></main><script>")
                .append("document.querySelectorAll('.step-link').forEach(function(btn){btn.addEventListener('click',function(){")
                .append("document.querySelectorAll('.step-link').forEach(function(item){item.classList.remove('active')});")
                .append("document.querySelectorAll('.detail').forEach(function(item){item.classList.remove('active')});")
                .append("btn.classList.add('active');var target=document.getElementById(btn.dataset.step);")
                .append("if(target){target.classList.add('active');target.scrollIntoView({behavior:'smooth',block:'start'});}});});")
                .append("</script></body></html>");
        return html.toString();
    }

    private static Map<String, Map<String, List<Integer>>> executionTree(List<Step> steps) {
        Map<String, Map<String, List<Integer>>> tree = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            tree.computeIfAbsent(step.suite(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(step.testCase(), key -> new ArrayList<>()).add(i);
        }
        return tree;
    }

    private static void appendTree(StringBuilder html, Map<String, Map<String, List<Integer>>> tree,
                                   List<Step> steps) {
        html.append("<aside><h2>Execution Tree</h2>");
        for (Map.Entry<String, Map<String, List<Integer>>> suiteEntry : tree.entrySet()) {
            html.append("<details open><summary>").append(escape(suiteEntry.getKey())).append("</summary>");
            for (Map.Entry<String, List<Integer>> caseEntry : suiteEntry.getValue().entrySet()) {
                html.append("<details class=\"case\" open><summary>").append(escape(caseEntry.getKey())).append("</summary>");
                for (Integer index : caseEntry.getValue()) {
                    Step step = steps.get(index);
                    html.append("<button class=\"step-link").append(index == 0 ? " active" : "")
                            .append("\" data-step=\"step-").append(index).append("\"><span class=\"dot ")
                            .append(step.passed() ? "pass" : "fail").append("\"></span><span>")
                            .append(escape(step.name())).append("</span></button>");
                }
                html.append("</details>");
            }
            html.append("</details>");
        }
        html.append("</aside>");
    }

    private static void appendStep(StringBuilder html, Step step, int index) {
        String firstFailure = step.passed() ? "" : step.validations().stream()
                .filter(validation -> !validation.passed())
                .map(Validation::message).filter(message -> !message.isBlank()).findFirst()
                .orElse(step.status());
        html.append("<article id=\"step-").append(index).append("\" class=\"detail")
                .append(index == 0 ? " active" : "").append("\"><div class=\"detail-head\"><div><h2>")
                .append(escape(step.name())).append("</h2><div class=\"meta\">")
                .append(escape(step.suite())).append(" / ").append(escape(step.testCase()))
                .append("</div></div><span class=\"pill ")
                .append(step.passed() ? "pass\">PASS" : "fail\">FAIL").append("</span></div>");
        html.append("<div class=\"facts\"><div class=\"fact\"><span>Step Type</span><b>")
                .append(escape(step.type())).append("</b></div><div class=\"fact\"><span>Status</span><b>")
                .append(escape(step.status())).append("</b></div><div class=\"fact\"><span>Validations</span><b>")
                .append(step.validations().size()).append("</b></div><div class=\"fact\"><span>Result</span><b>")
                .append(step.passed() ? "Passed" : "Failed").append("</b></div></div>");
        if (!firstFailure.isBlank()) {
            html.append("<div class=\"error\"><b>Failure Error Message</b><br>")
                    .append(escape(firstFailure)).append("</div>");
        }
        html.append("<table><thead><tr><th>Status</th><th>Field</th><th>Validation</th><th>Expected</th><th>Actual</th><th>Message</th></tr></thead><tbody>");
        if (step.validations().isEmpty()) {
            html.append("<tr><td>").append(step.passed() ? "<span class=\"ok\">PASS</span>" : "<span class=\"bad\">FAIL</span>")
                    .append("</td><td>").append(escape(step.name())).append("</td><td>").append(escape(step.type()))
                    .append("</td><td><pre></pre></td><td><pre></pre></td><td>").append(escape(step.status())).append("</td></tr>");
        } else {
            for (Validation validation : step.validations()) {
                html.append("<tr><td>").append(validation.passed() ? "<span class=\"ok\">PASS</span>" : "<span class=\"bad\">FAIL</span>")
                        .append("</td><td>").append(escape(validation.field())).append("</td><td>").append(escape(validation.validation()))
                        .append("</td><td><pre>").append(escape(validation.expected())).append("</pre></td><td><pre>")
                        .append(escape(validation.actual())).append("</pre></td><td>").append(escape(validation.message())).append("</td></tr>");
            }
        }
        html.append("</tbody></table></article>");
    }

    private static String metric(String label, String value) {
        return "<div class=\"metric\"><b>" + escape(value) + "</b>" + escape(label) + "</div>";
    }

    private static String escape(String value) {
        return safe(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
