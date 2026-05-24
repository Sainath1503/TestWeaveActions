package model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WebTestRunReport {

    public List<WebTestExecutionResult> results = new ArrayList<>();
    public int total;
    public int passed;
    public int failed;
    public long totalDurationMs;
    public Path lastScreenshotPath;
    public boolean stopped;
}
