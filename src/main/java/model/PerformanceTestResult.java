package model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class PerformanceTestResult {

    public String method;
    public String endpoint;
    public int threads;
    public int iterationsPerThread;
    public long samples;
    public long errors;
    public double errorPercent;
    public double throughputPerSecond;
    public Duration duration;
    public Duration min;
    public Duration mean;
    public Duration median;
    public Duration perc90;
    public Duration perc95;
    public Duration perc99;
    public Duration max;
    public Path reportIndexPath;
    public Path reportDirectory;
    public Path requestCaptureJsonPath;
    public final Map<String, Double> chartValuesMs = new LinkedHashMap<>();
}
