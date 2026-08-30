package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.moxie.confer.proxy.documents.DecryptedDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerArtifactValidatorTest {

  @TempDir
  private Path outputDirectory;

  @Test
  void validatesThatTheCombinedWorkbookContainsBothInputs() throws IOException {
    WorkerArtifactValidator.Evidence complete = validate(getWorkbook(true));
    WorkerArtifactValidator.Evidence incomplete = validate(getWorkbook(false));
    WorkerArtifactValidator.Evidence extraColumn = validate(
        addExtraColumn(getWorkbook(true)));

    assertTrue(complete.passed());
    assertFalse(incomplete.passed());
    assertFalse(extraColumn.passed());
  }

  @Test
  void rejectsAFileChangedAfterPublication() throws IOException {
    LocalAttachmentPublisher publisher = publish(getWorkbook(true));
    Files.write(
        publisher.getPublished().path(),
        new byte[] {0},
        StandardOpenOption.APPEND);

    assertFalse(new WorkerArtifactValidator(publisher).validate(getSpec()).passed());
  }

  private WorkerArtifactValidator.Evidence validate(byte[] content) throws IOException {
    return new WorkerArtifactValidator(publish(content)).validate(getSpec());
  }

  private LocalAttachmentPublisher publish(byte[] content) throws IOException {
    LocalAttachmentPublisher publisher = new LocalAttachmentPublisher(
        outputDirectory.resolve(UUID.randomUUID().toString()));
    publisher.publish(
        "generated-attachments",
        "combined-sales.xlsx",
        new ByteArrayInputStream(content));
    return publisher;
  }

  private static ObjectNode getSpec() {
    ObjectNode spec = new ObjectMapper().createObjectNode();
    spec.put("format", "xlsx");
    spec.put("profile", "xlsx_attachment_combine");
    spec.putArray("required_text");
    return spec;
  }

  private static byte[] getWorkbook(boolean includeEveryRow) throws IOException {
    WorkerEvalInputSource inputs = new WorkerEvalInputSource();
    try (DecryptedDocument q1 = inputs.open("attachment-q1-sales");
         DecryptedDocument q2 = inputs.open("attachment-q2-sales");
         XSSFWorkbook workbook = new XSSFWorkbook(q1.content());
         XSSFWorkbook second = new XSSFWorkbook(q2.content());
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.setSheetName(0, "Combined Sales");
      XSSFSheet destination = workbook.getSheetAt(0);
      XSSFSheet source = second.getSheetAt(0);
      copyRow(source.getRow(1), destination.createRow(3), destination.getRow(1));
      if (includeEveryRow) {
        copyRow(source.getRow(2), destination.createRow(4), destination.getRow(1));
        destination.setAutoFilter(new CellRangeAddress(0, 4, 0, 3));
      } else {
        destination.setAutoFilter(new CellRangeAddress(0, 3, 0, 3));
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static byte[] addExtraColumn(byte[] content) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(
        new ByteArrayInputStream(content));
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).getRow(0).createCell(4).setCellValue("Unexpected");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void copyRow(Row source, Row destination, Row styleSource) {
    for (int column = 0; column < 4; column++) {
      Cell sourceCell = source.getCell(column);
      Cell destinationCell = destination.createCell(column);
      if (column == 3) {
        destinationCell.setCellValue(sourceCell.getNumericCellValue());
        destinationCell.setCellStyle(styleSource.getCell(column).getCellStyle());
      } else {
        destinationCell.setCellValue(sourceCell.getStringCellValue());
      }
    }
  }
}
