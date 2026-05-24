package model;

public class WebTestExecutionResult {

    public String action;
    public String selector;
    public String expectedValue;
    public String capturedVariableName;
    public String capturedVariableValue;
    public boolean passed;
    public String message;
    public long durationMs;
}
