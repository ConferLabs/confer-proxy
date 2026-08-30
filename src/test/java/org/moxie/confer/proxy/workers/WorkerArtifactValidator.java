package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

class WorkerArtifactValidator {

  private static final String XLSX_PROFILE = "xlsx_attachment_combine";
  private static final List<String> SALES_HEADER = List.of(
      "Region",
      "Product",
      "Quarter",
      "Revenue USD");
  private static final Set<SalesRow> SALES_ROWS = Set.of(
      new SalesRow("Northwest", "Private Chat", "Q1 2026", 127_431),
      new SalesRow("Southeast", "Confidential Search", "Q1 2026", 88_967),
      new SalesRow("Northeast", "Document Studio", "Q2 2026", 134_219),
      new SalesRow("Southwest", "Knowledge Search", "Q2 2026", 94_583));

  private final LocalAttachmentPublisher publisher;

  WorkerArtifactValidator(LocalAttachmentPublisher publisher) {
    this.publisher = publisher;
  }

  Evidence validate(JsonNode value) {
    Spec spec = getSpec(value);
    LocalAttachmentPublisher.PublishedFile published = publisher.getPublished();
    Inspection inspection = inspect(published.path(), spec);
    String digest = getSha256(published.path());
    boolean textValid = spec.requiredText().stream()
        .allMatch(inspection.text()::contains);
    boolean passed = inspection.formatValid()
        && textValid
        && inspection.profileValid()
        && digest.equals(published.sha256());
    return new Evidence(passed, digest);
  }

  private static Inspection inspect(Path path, Spec spec) {
    return switch (spec.format()) {
      case "docx" -> inspectDocx(path);
      case "xlsx" -> inspectXlsx(path, spec.profile());
      default -> throw new IllegalArgumentException("Unsupported artifact format");
    };
  }

  private static Inspection inspectDocx(Path path) {
    try (InputStream input = Files.newInputStream(path);
         XWPFDocument document = new XWPFDocument(input);
         XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
      return new Inspection(true, extractor.getText(), true);
    } catch (IOException error) {
      return Inspection.invalid();
    }
  }

  private static Inspection inspectXlsx(Path path, String profile) {
    try (InputStream input = Files.newInputStream(path);
         XSSFWorkbook workbook = new XSSFWorkbook(input)) {
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      StringBuilder text = new StringBuilder();
      for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
        XSSFSheet sheet = workbook.getSheetAt(index);
        text.append(sheet.getSheetName()).append('\n');
        for (Row row : sheet) {
          for (Cell cell : row) {
            text.append(formatter.formatCellValue(cell)).append('\n');
          }
        }
      }
      boolean profileValid = profile == null
          || XLSX_PROFILE.equals(profile) && isCombinedSalesWorkbook(workbook);
      return new Inspection(true, text.toString(), profileValid);
    } catch (IOException error) {
      return Inspection.invalid();
    }
  }

  private static boolean isCombinedSalesWorkbook(XSSFWorkbook workbook) {
    XSSFSheet sheet = workbook.getSheet("Combined Sales");
    if (sheet == null
        || workbook.getNumberOfSheets() != 1
        || workbook.getSheetVisibility(0) != SheetVisibility.VISIBLE
        || sheet.getFirstRowNum() != 0
        || sheet.getLastRowNum() != 4
        || !getHeader(sheet).equals(SALES_HEADER)
        || !new HashSet<>(getRows(sheet)).equals(SALES_ROWS)) {
      return false;
    }

    PaneInformation pane = sheet.getPaneInformation();
    return hasExpectedStyles(workbook, sheet)
        && pane != null
        && pane.isFreezePane()
        && pane.getHorizontalSplitPosition() == 1
        && pane.getVerticalSplitPosition() == 0
        && sheet.getCTWorksheet().isSetAutoFilter()
        && "A1:D5".equals(sheet.getCTWorksheet().getAutoFilter().getRef());
  }

  private static List<String> getHeader(XSSFSheet sheet) {
    Row row = sheet.getRow(0);
    if (row == null || row.getLastCellNum() != SALES_HEADER.size()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (int column = 0; column < SALES_HEADER.size(); column++) {
      Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      if (cell == null || cell.getCellType() != CellType.STRING) {
        return List.of();
      }
      values.add(cell.getStringCellValue());
    }
    return List.copyOf(values);
  }

  private static List<SalesRow> getRows(XSSFSheet sheet) {
    List<SalesRow> rows = new ArrayList<>();
    for (int number = 1; number <= SALES_ROWS.size(); number++) {
      Row row = sheet.getRow(number);
      if (row == null
          || row.getLastCellNum() != SALES_HEADER.size()
          || !hasCell(row, 0, CellType.STRING)
          || !hasCell(row, 1, CellType.STRING)
          || !hasCell(row, 2, CellType.STRING)
          || !hasCell(row, 3, CellType.NUMERIC)) {
        return List.of();
      }
      rows.add(new SalesRow(
          row.getCell(0).getStringCellValue(),
          row.getCell(1).getStringCellValue(),
          row.getCell(2).getStringCellValue(),
          row.getCell(3).getNumericCellValue()));
    }
    return List.copyOf(rows);
  }

  private static boolean hasCell(Row row, int column, CellType type) {
    Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    return cell != null && cell.getCellType() == type;
  }

  private static boolean hasExpectedStyles(XSSFWorkbook workbook, XSSFSheet sheet) {
    Row header = sheet.getRow(0);
    for (int column = 0; column < SALES_HEADER.size(); column++) {
      Cell cell = header.getCell(column);
      Font font = workbook.getFontAt(cell.getCellStyle().getFontIndex());
      if (!font.getBold()
          || font.getColor() != IndexedColors.WHITE.getIndex()
          || cell.getCellStyle().getFillPattern() != FillPatternType.SOLID_FOREGROUND
          || cell.getCellStyle().getFillForegroundColor() != IndexedColors.DARK_BLUE.getIndex()) {
        return false;
      }
    }
    for (int number = 1; number <= SALES_ROWS.size(); number++) {
      if (!"$#,##0".equals(
          sheet.getRow(number).getCell(3).getCellStyle().getDataFormatString())) {
        return false;
      }
    }
    return true;
  }

  private static Spec getSpec(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Artifact validation is invalid");
    }
    String format = getText(value.get("format"));
    String profile = getOptionalText(value.get("profile"));
    JsonNode requiredText = value.get("required_text");
    if (!("docx".equals(format) || "xlsx".equals(format))
        || (profile != null && !("xlsx".equals(format) && XLSX_PROFILE.equals(profile)))
        || requiredText == null
        || !requiredText.isArray()) {
      throw new IllegalArgumentException("Artifact validation is invalid");
    }

    List<String> required = new ArrayList<>();
    for (JsonNode item : requiredText) {
      String text = getText(item);
      if (text == null) {
        throw new IllegalArgumentException("Artifact validation is invalid");
      }
      required.add(text);
    }
    return new Spec(format, profile, List.copyOf(required));
  }

  private static String getText(JsonNode value) {
    return value != null && value.isTextual() && !value.textValue().isBlank()
        ? value.textValue()
        : null;
  }

  private static String getOptionalText(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    String text = getText(value);
    if (text == null) {
      throw new IllegalArgumentException("Artifact validation is invalid");
    }
    return text;
  }

  private static String getSha256(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new IllegalStateException("Could not hash the published artifact", error);
    }
  }

  private record Spec(String format,
                      String profile,
                      List<String> requiredText) {}

  record Evidence(boolean passed,
                  String  sha256) {}

  private record Inspection(boolean formatValid,
                            String  text,
                            boolean profileValid) {

    private static Inspection invalid() {
      return new Inspection(false, "", false);
    }
  }

  private record SalesRow(String region,
                          String product,
                          String quarter,
                          double revenue) {}
}
