package model;

import java.util.List;
import java.util.Map;

public class DbValidationReport {

    public List<DbValidationResult> results;
    public int total;
    public int passed;
    public int failed;
    public List<Map<String, Object>> dbRows;
    public String executedSql;
}
