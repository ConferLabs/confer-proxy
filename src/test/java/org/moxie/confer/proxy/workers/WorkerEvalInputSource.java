package org.moxie.confer.proxy.workers;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.moxie.confer.proxy.documents.DecryptedDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

class WorkerEvalInputSource implements WorkerInputSource {

  private static final Map<String, byte[]> INPUTS = Map.of(
      "attachment-q1-sales",
      createWorkbook(
          "Q1 Sales",
          new SalesRow("Northwest", "Private Chat", "Q1 2026", 127_431),
          new SalesRow("Southeast", "Confidential Search", "Q1 2026", 88_967)),
      "attachment-q2-sales",
      createWorkbook(
          "Q2 Sales",
          new SalesRow("Northeast", "Document Studio", "Q2 2026", 134_219),
          new SalesRow("Southwest", "Knowledge Search", "Q2 2026", 94_583)));

  @Override
  public DecryptedDocument open(String attachmentId) throws IOException {
    byte[] content = INPUTS.get(attachmentId);
    if (content == null) {
      throw new IOException("Unknown attachment_id");
    }
    return new DecryptedDocument(
        new ByteArrayInputStream(content),
        content.length);
  }

  private static byte[] createWorkbook(String sheetName,
                                       SalesRow first,
                                       SalesRow second)
  {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = workbook.createSheet(sheetName);
      CellStyle headerStyle = getHeaderStyle(workbook);
      CellStyle revenueStyle = workbook.createCellStyle();
      revenueStyle.setDataFormat(workbook.createDataFormat().getFormat("$#,##0"));

      Row header = sheet.createRow(0);
      String[] headings = {"Region", "Product", "Quarter", "Revenue USD"};
      for (int column = 0; column < headings.length; column++) {
        Cell cell = header.createCell(column);
        cell.setCellValue(headings[column]);
        cell.setCellStyle(headerStyle);
      }
      addRow(sheet, 1, first, revenueStyle);
      addRow(sheet, 2, second, revenueStyle);
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, 2, 0, 3));
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Could not create a worker eval input", error);
    }
  }

  private static CellStyle getHeaderStyle(XSSFWorkbook workbook) {
    Font font = workbook.createFont();
    font.setBold(true);
    font.setColor(IndexedColors.WHITE.getIndex());

    CellStyle style = workbook.createCellStyle();
    style.setFont(font);
    style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return style;
  }

  private static void addRow(XSSFSheet sheet,
                             int       number,
                             SalesRow  value,
                             CellStyle revenueStyle)
  {
    Row row = sheet.createRow(number);
    row.createCell(0).setCellValue(value.region());
    row.createCell(1).setCellValue(value.product());
    row.createCell(2).setCellValue(value.quarter());
    Cell revenue = row.createCell(3);
    revenue.setCellValue(value.revenue());
    revenue.setCellStyle(revenueStyle);
  }

  private record SalesRow(String region,
                          String product,
                          String quarter,
                          double revenue) {}
}
