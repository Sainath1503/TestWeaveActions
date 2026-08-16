package agenticai.testplanning;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic Excel publisher for Test Strategy, Test Cases, Test Data, and Traceability. */
public final class TestWorkbookMcpServer {
    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-test-workbook-mcp")
                        .put("version", "1.0").put("mode", "in-process").put("readOnly", false))
                .put("tools", new JSONArray().put("workbook.generate_test_plan").put("workbook.validate_links"));
    }

    public Path write(JSONObject result, Path outputPath) throws Exception {
        Path normalized = outputPath.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = createStyles(workbook);
            writeStrategy(workbook, styles, result.optJSONArray("testStrategy"));
            writeCases(workbook, styles, result.optJSONArray("testCases"));
            writeData(workbook, styles, result.optJSONArray("testData"));
            writeTraceability(workbook, styles, result.optJSONArray("traceability"));
            workbook.setActiveSheet(0);
            try (OutputStream output = Files.newOutputStream(normalized)) {
                workbook.write(output);
            }
        }
        return normalized;
    }

    private void writeStrategy(XSSFWorkbook workbook, Styles styles, JSONArray rows) {
        writeJsonSheet(workbook, styles, "Test Strategy",
                List.of("Section", "Item", "Details", "Priority", "Source References"),
                rows, List.of("section", "item", "details", "priority", "sourceReferences"));
    }

    private void writeCases(XSSFWorkbook workbook, Styles styles, JSONArray rows) {
        writeJsonSheet(workbook, styles, "Test Cases",
                List.of("Test Case ID", "Requirement ID", "Module", "Scenario", "Preconditions", "Steps",
                        "Base URI", "Endpoint", "HTTP Method", "Request Payload", "Expected Return Code",
                        "Expected Result", "Test Type", "Priority", "Classification", "Automation Candidate",
                        "Test Data IDs", "Source References"),
                rows, List.of("testCaseId", "requirementId", "module", "scenario", "preconditions", "steps",
                        "baseUri", "endpoint", "httpMethod", "requestPayload", "expectedReturnCode",
                        "expectedResult", "testType", "priority", "classification", "automationCandidate",
                        "testDataIds", "sourceReferences"));
    }

    private void writeData(XSSFWorkbook workbook, Styles styles, JSONArray rows) {
        writeJsonSheet(workbook, styles, "Test Data",
                List.of("Test Data ID", "Test Case IDs", "Field", "Valid Value", "Invalid Value", "Boundary Value",
                        "Setup", "Cleanup", "Sensitivity", "Notes"),
                rows, List.of("testDataId", "testCaseIds", "field", "validValue", "invalidValue", "boundaryValue",
                        "setup", "cleanup", "sensitivity", "notes"));
    }

    private void writeTraceability(XSSFWorkbook workbook, Styles styles, JSONArray rows) {
        writeJsonSheet(workbook, styles, "Traceability",
                List.of("Requirement ID", "Requirement", "Source Reference", "Test Case IDs", "Coverage Status", "Notes"),
                rows, List.of("requirementId", "requirement", "sourceReference", "testCaseIds", "coverageStatus", "notes"));
    }

    private void writeJsonSheet(XSSFWorkbook workbook, Styles styles, String name, List<String> headers,
                                JSONArray values, List<String> fields) {
        Sheet sheet = workbook.createSheet(name);
        sheet.createFreezePane(0, 1);
        Row header = sheet.createRow(0);
        header.setHeightInPoints(28);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header());
        }
        JSONArray rows = values == null ? new JSONArray() : values;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject value = rows.optJSONObject(index);
            if (value == null) continue;
            Row row = sheet.createRow(index + 1);
            for (int column = 0; column < fields.size(); column++) {
                Cell cell = row.createCell(column);
                cell.setCellValue(display(value.opt(fields.get(column))));
                cell.setCellStyle(index % 2 == 0 ? styles.body() : styles.alternateBody());
            }
        }
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.length()), 0, headers.size() - 1));
        for (int column = 0; column < headers.size(); column++) {
            int width = headers.get(column).toLowerCase().matches(".*(steps|details|scenario|expected|requirement|notes).*")
                    ? 16_000 : 6_000;
            sheet.setColumnWidth(column, Math.min(25_000, width));
        }
    }

    private String display(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return "";
        if (value instanceof JSONArray array) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) lines.add((i + 1) + ". " + array.optString(i));
            return String.join("\n", lines);
        }
        if (value instanceof JSONObject object) return object.toString();
        return String.valueOf(value);
    }

    private Styles createStyles(XSSFWorkbook workbook) {
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        addBorders(header);

        CellStyle body = workbook.createCellStyle();
        body.setWrapText(true);
        body.setVerticalAlignment(VerticalAlignment.TOP);
        addBorders(body);

        CellStyle alternate = workbook.createCellStyle();
        alternate.cloneStyleFrom(body);
        alternate.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        alternate.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return new Styles(header, body, alternate);
    }

    private void addBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
    }

    private record Styles(CellStyle header, CellStyle body, CellStyle alternateBody) {
    }
}
