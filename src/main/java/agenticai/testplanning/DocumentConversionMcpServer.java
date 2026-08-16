package agenticai.testplanning;

import org.json.JSONArray;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MCP-style document conversion tools used by the Test Planning Agent.
 * Extracted text is deliberately bounded before it is passed to a model.
 */
public final class DocumentConversionMcpServer {
    public static final int MAX_EXTRACTED_CHARACTERS = 250_000;

    public JSONObject extract(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Source file does not exist: " + normalized);
        }
        String extension = extension(normalized);
        String content = switch (extension) {
            case "xlsx", "xls" -> extractWorkbook(normalized);
            case "csv" -> extractCsv(normalized);
            case "pdf" -> extractPdf(normalized);
            case "docx" -> extractDocx(normalized);
            case "pptx" -> extractPptx(normalized);
            case "ppt" -> extractPpt(normalized);
            case "json" -> Files.readString(normalized, StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("Unsupported upload type: ." + extension);
        };
        boolean truncated = content.length() > MAX_EXTRACTED_CHARACTERS;
        if (truncated) {
            content = content.substring(0, MAX_EXTRACTED_CHARACTERS);
        }
        return new JSONObject()
                .put("sourceType", "upload")
                .put("name", normalized.getFileName().toString())
                .put("path", normalized.toString())
                .put("format", extension)
                .put("sha256", sha256(normalized))
                .put("sizeBytes", Files.size(normalized))
                .put("truncated", truncated)
                .put("content", content);
    }

    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-document-conversion-mcp")
                        .put("version", "1.0").put("mode", "in-process").put("readOnly", true))
                .put("tools", new JSONArray().put("documents.extract").put("documents.supports"));
    }

    public boolean supports(Path path) {
        return switch (extension(path)) {
            case "xlsx", "xls", "csv", "pdf", "docx", "pptx", "ppt", "json" -> true;
            default -> false;
        };
    }

    private String extractWorkbook(Path path) throws Exception {
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            for (Sheet sheet : workbook) {
                append(text, "\n[SHEET: " + sheet.getSheetName() + "]\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getColumnIndex() > 0) append(text, "\t");
                        append(text, formatter.formatCellValue(cell));
                    }
                    append(text, "\n");
                    if (text.length() >= MAX_EXTRACTED_CHARACTERS) return text.toString();
                }
            }
        }
        return text.toString();
    }

    private String extractCsv(Path path) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            long row = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                append(text, "[ROW " + (++row) + "] " + String.join(" | ", parseCsvLine(line)) + "\n");
                if (text.length() >= MAX_EXTRACTED_CHARACTERS) break;
            }
        }
        return text.toString();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString());
        return values;
    }

    private String extractPdf(Path path) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(document);
            if (text.isBlank()) {
                return "[No embedded PDF text was found. The document may require OCR.]";
            }
            return text;
        }
    }

    private String extractDocx(Path path) throws Exception {
        StringBuilder text = new StringBuilder();
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                append(text, paragraph.getText() + "\n");
            }
            for (XWPFTable table : document.getTables()) {
                append(text, "[TABLE]\n");
                table.getRows().forEach(row -> append(text,
                        row.getTableCells().stream().map(cell -> cell.getText()).reduce((a, b) -> a + " | " + b).orElse("") + "\n"));
            }
        }
        return text.toString();
    }

    private String extractPptx(Path path) throws Exception {
        StringBuilder text = new StringBuilder();
        try (InputStream input = Files.newInputStream(path); XMLSlideShow presentation = new XMLSlideShow(input)) {
            int number = 0;
            for (XSLFSlide slide : presentation.getSlides()) {
                append(text, "\n[SLIDE " + (++number) + "]\n");
                for (Shape<?, ?> shape : slide.getShapes()) {
                    if (shape instanceof TextShape<?, ?> textShape) append(text, textShape.getText() + "\n");
                }
            }
        }
        return text.toString();
    }

    private String extractPpt(Path path) throws Exception {
        StringBuilder text = new StringBuilder();
        try (InputStream input = Files.newInputStream(path); HSLFSlideShow presentation = new HSLFSlideShow(input)) {
            int number = 0;
            for (HSLFSlide slide : presentation.getSlides()) {
                append(text, "\n[SLIDE " + (++number) + "]\n");
                for (Shape<?, ?> shape : slide.getShapes()) {
                    if (shape instanceof TextShape<?, ?> textShape) append(text, textShape.getText() + "\n");
                }
            }
        }
        return text.toString();
    }

    private void append(StringBuilder target, String value) {
        if (target.length() < MAX_EXTRACTED_CHARACTERS && value != null) target.append(value);
    }

    private String extension(Path path) {
        String name = path == null || path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
