package com.learnthink.core.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class FileParserService {

    private static final Logger log = LoggerFactory.getLogger(FileParserService.class);

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".txt", ".md", ".csv", ".json", ".xml", ".yaml", ".yml",
        ".log", ".properties", ".ini", ".cfg", ".conf", ".env", ".toml"
    );

    private static final Set<String> CODE_EXTENSIONS = Set.of(
        ".py", ".js", ".ts", ".tsx", ".jsx", ".vue", ".java", ".c", ".cpp",
        ".h", ".hpp", ".cs", ".go", ".rs", ".rb", ".php", ".swift", ".kt",
        ".scala", ".sh", ".bat", ".ps1", ".sql", ".r", ".dart", ".lua", ".pl",
        ".m", ".mm", ".groovy", ".gradle", ".ktm", ".kts", ".d", ".jl",
        ".zig", ".nim", ".ex", ".exs", ".erl", ".hrl", ".clj", ".cljs",
        ".edn", ".fs", ".fsx", ".hs", ".lhs", ".ml", ".mli", ".v", ".vhdl",
        ".tex", ".bib", ".rst", ".asciidoc", ".adoc", "./"
    );

    private static final Set<String> HTML_EXTENSIONS = Set.of(".html", ".htm", ".xhtml");

    private static final Set<String> PDF_EXTENSIONS = Set.of(".pdf");

    private static final Set<String> DOCX_EXTENSIONS = Set.of(".docx");

    private static final Set<String> PPTX_EXTENSIONS = Set.of(".pptx");

    private static final Set<String> EXCEL_EXTENSIONS = Set.of(".xlsx", ".xls");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tiff", ".tif"
    );

    public ParseResult parse(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return new ParseResult("", 0, "unknown", false, "无法识别文件名");
        }

        String ext = getExtension(originalName).toLowerCase();
        long size = file.getSize();
        String contentType = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");

        try {
            if (IMAGE_EXTENSIONS.contains(ext)) {
                return parseImage(file, originalName, ext, size);
            }
            if (TEXT_EXTENSIONS.contains(ext)) {
                return parseTextFile(file, originalName, ext, size);
            }
            if (CODE_EXTENSIONS.contains(ext)) {
                return parseTextFile(file, originalName, ext, size);
            }
            if (HTML_EXTENSIONS.contains(ext)) {
                return parseHtml(file, originalName, ext, size);
            }
            if (PDF_EXTENSIONS.contains(ext)) {
                return parsePdf(file, originalName, ext, size);
            }
            if (DOCX_EXTENSIONS.contains(ext)) {
                return parseDocx(file, originalName, ext, size);
            }
            if (PPTX_EXTENSIONS.contains(ext)) {
                return parsePptx(file, originalName, ext, size);
            }
            if (EXCEL_EXTENSIONS.contains(ext)) {
                return parseExcel(file, originalName, ext, size);
            }
            return new ParseResult("", size, contentType, false,
                "不支持的文件类型: " + ext + "（" + originalName + "）");
        } catch (Exception e) {
            log.error("Failed to parse file: {}", originalName, e);
            return new ParseResult("", size, contentType, false,
                "文件解析失败: " + e.getMessage());
        }
    }

    private ParseResult parseTextFile(MultipartFile file, String name, String ext, long size) throws IOException {
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (text.length() > 100_000) {
            text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
        }
        return new ParseResult(text, size, guessContentType(ext), false, null);
    }

    private ParseResult parseHtml(MultipartFile file, String name, String ext, long size) throws IOException {
        org.jsoup.nodes.Document doc = Jsoup.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        String text = doc.body().text();
        if (text.length() > 100_000) {
            text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
        }
        return new ParseResult(text, size, "text/html", false, null);
    }

    private ParseResult parsePdf(MultipartFile file, String name, String ext, long size) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text.length() > 100_000) {
                text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
            }
            return new ParseResult(text, size, "application/pdf", false, null);
        }
    }

    private ParseResult parseDocx(MultipartFile file, String name, String ext, long size) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            if (text.length() > 100_000) {
                text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
            }
            return new ParseResult(text, size, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, null);
        }
    }

    private ParseResult parsePptx(MultipartFile file, String name, String ext, long size) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow slideShow = new XMLSlideShow(file.getInputStream())) {
            for (XSLFSlide slide : slideShow.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t).append("\n");
                        }
                    }
                }
                sb.append("--- 幻灯片分隔 ---\n");
            }
        } catch (Exception e) {
            throw new IOException("PPTX解析失败", e);
        }
        String text = sb.toString().trim();
        if (text.length() > 100_000) {
            text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
        }
        return new ParseResult(text, size, "application/vnd.openxmlformats-officedocument.presentationml.presentation", false, null);
    }

    private ParseResult parseExcel(MultipartFile file, String name, String ext, long size) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                if (sb.length() > 100_000) break;
                sb.append("## ").append(sheetName != null ? sheetName : "Sheet" + (i + 1)).append("\n");
                int maxCols = 0;
                for (Row row : sheet) {
                    if (row.getLastCellNum() > maxCols) {
                        maxCols = row.getLastCellNum();
                    }
                }
                String sep = "| " + "--- |".repeat(maxCols > 0 ? maxCols : 1) + "\n";
                boolean headerRow = true;
                for (Row row : sheet) {
                    if (sb.length() > 90_000) break;
                    sb.append("|");
                    for (int c = 0; c < maxCols; c++) {
                        Cell cell = row.getCell(c);
                        String val = getCellValue(cell).replace("|", "\\|");
                        sb.append(" ").append(val).append(" |");
                    }
                    sb.append("\n");
                    if (headerRow) {
                        sb.append(sep);
                        headerRow = false;
                    }
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            throw new IOException("Excel解析失败", e);
        }
        String text = sb.toString().trim();
        if (text.length() > 100_000) {
            text = text.substring(0, 100_000) + "\n\n...（文件过长，已截取前 100,000 字符）";
        }
        String contentType = ".xlsx".equals(ext)
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "application/vnd.ms-excel";
        return new ParseResult(text, size, contentType, false, null);
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    double val = cell.getNumericCellValue();
                    if (val == Math.floor(val) && !Double.isInfinite(val)) {
                        yield String.valueOf((long) val);
                    }
                    yield String.valueOf(val);
                } catch (Exception e1) {
                    try {
                        yield cell.getStringCellValue();
                    } catch (Exception e2) {
                        yield cell.getCellFormula();
                    }
                }
            }
            case BLANK -> "";
            default -> "";
        };
    }

    private ParseResult parseImage(MultipartFile file, String name, String ext, long size) {
        return new ParseResult("", size, "image/" + ext.replace(".", ""), true,
            "【图片文件：" + name + "（" + formatSize(size) + "）】\n图片内容解析暂未实现，后续版本将支持 OCR 识别。");
    }

    private String getExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }

    private String guessContentType(String ext) {
        return switch (ext) {
            case ".txt" -> "text/plain";
            case ".md" -> "text/markdown";
            case ".csv" -> "text/csv";
            case ".json" -> "application/json";
            case ".xml" -> "application/xml";
            case ".yaml", ".yml" -> "application/x-yaml";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".xls" -> "application/vnd.ms-excel";
            case ".html", ".htm" -> "text/html";
            case ".py" -> "text/x-python";
            case ".js" -> "text/javascript";
            case ".ts", ".tsx" -> "text/typescript";
            case ".java" -> "text/x-java";
            case ".c" -> "text/x-c";
            case ".cpp", ".cc" -> "text/x-c++";
            case ".cs" -> "text/x-csharp";
            case ".go" -> "text/x-go";
            case ".rs" -> "text/x-rust";
            case ".rb" -> "text/x-ruby";
            case ".php" -> "text/x-php";
            case ".swift" -> "text/x-swift";
            case ".kt", ".kts" -> "text/x-kotlin";
            case ".sql" -> "text/x-sql";
            case ".sh" -> "text/x-shellscript";
            default -> "text/plain";
        };
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public record ParseResult(
        String parsedText,
        long fileSize,
        String contentType,
        boolean isImage,
        String errorMessage
    ) {
        public boolean isSuccess() { return errorMessage == null; }
    }
}
