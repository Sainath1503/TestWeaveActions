package model;

public class WebTestExecutionResult {

    public String stepName;
    public String action;
    public String selector;
    public String expectedValue;
    public String actualValue;
    public String observedVariableName;
    public String observedVariableValue;
    public String capturedVariableName;
    public String capturedVariableValue;
    public boolean passed;
    public String message;
    public long durationMs;
    public String pageUrl;
    public String pageTitle;
    public String screenshotPath;
    public String ariaSnapshot;
    public String domSnapshot;
    public String consoleMessages;
    public String networkFailures;
}
