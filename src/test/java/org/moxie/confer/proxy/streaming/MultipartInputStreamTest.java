package org.moxie.confer.proxy.streaming;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartInputStreamTest {

  @Test
  void producesValidMultipartFormat() throws IOException {
    byte[] fileContent = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent);

    MultipartInputStream multipart = new MultipartInputStream(
        "test-boundary",
        fileStream,
        "files",
        "test.txt",
        "text/plain"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    String expected = "--test-boundary\r\n"
        + "Content-Disposition: form-data; name=\"files\"; filename=\"test.txt\"\r\n"
        + "Content-Type: text/plain\r\n"
        + "\r\n"
        + "Hello, World!"
        + "\r\n--test-boundary--\r\n";

    assertEquals(expected, result);
  }

  @Test
  void usesCustomFieldName() throws IOException {
    byte[] fileContent = "data".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent);

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "file",
        "doc.pdf",
        "application/pdf"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(result.contains("name=\"file\""), "Should use custom field name 'file'");
    assertFalse(result.contains("name=\"files\""), "Should not use 'files' field name");
  }

  @Test
  void handlesEmptyFileContent() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream(new byte[0]);

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary123",
        fileStream,
        "files",
        "empty.bin",
        "application/octet-stream"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(result.startsWith("--boundary123\r\n"));
    assertTrue(result.contains("filename=\"empty.bin\""));
    assertTrue(result.endsWith("\r\n--boundary123--\r\n"));
  }

  @Test
  void escapesQuotesInFilename() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "file\"with\"quotes.txt",
        "text/plain"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(result.contains("filename=\"file\\\"with\\\"quotes.txt\""));
  }

  @Test
  void escapesBackslashesInFilename() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "path\\to\\file.txt",
        "text/plain"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(result.contains("filename=\"path\\\\to\\\\file.txt\""));
  }

  @Test
  void readSingleByteAtATime() throws IOException {
    byte[] fileContent = "ABC".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent);

    MultipartInputStream multipart = new MultipartInputStream(
        "b",
        fileStream,
        "files",
        "f.txt",
        "text/plain"
    );

    StringBuilder result = new StringBuilder();
    int b;
    while ((b = multipart.read()) != -1) {
      result.append((char) b);
    }

    assertTrue(result.toString().contains("ABC"));
    assertTrue(result.toString().startsWith("--b\r\n"));
    assertTrue(result.toString().endsWith("\r\n--b--\r\n"));
  }

  @Test
  void readWithSmallBuffer() throws IOException {
    byte[] fileContent = "This is a longer file content for testing buffered reads.".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent);

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.txt",
        "text/plain"
    );

    // Read with a small buffer to test partial reads across state transitions
    byte[] buffer = new byte[10];
    StringBuilder result = new StringBuilder();
    int bytesRead;
    while ((bytesRead = multipart.read(buffer, 0, buffer.length)) != -1) {
      result.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
    }

    assertTrue(result.toString().contains("This is a longer file content"));
    assertTrue(result.toString().startsWith("--boundary\r\n"));
    assertTrue(result.toString().endsWith("\r\n--boundary--\r\n"));
  }

  @Test
  void closesUnderlyingStream() throws IOException {
    boolean[] closed = {false};
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes()) {
      @Override
      public void close() throws IOException {
        closed[0] = true;
        super.close();
      }
    };

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.txt",
        "text/plain"
    );

    multipart.close();

    assertTrue(closed[0]);
  }

  @Test
  void returnsMinusOneAfterFullyRead() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.txt",
        "text/plain"
    );

    // Read everything
    multipart.readAllBytes();

    // Subsequent reads should return -1
    assertEquals(-1, multipart.read());
    assertEquals(-1, multipart.read(new byte[10], 0, 10));
  }

  @Test
  void includesFormFieldsBeforeFile() throws IOException {
    byte[] fileContent = "file data".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent);

    List<MultipartInputStream.FormField> formFields = List.of(
        new MultipartInputStream.FormField("do_ocr", "true"),
        new MultipartInputStream.FormField("do_table_structure", "false")
    );

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.pdf",
        "application/pdf",
        formFields
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    // Form fields should appear before file
    int ocrFieldPos = result.indexOf("name=\"do_ocr\"");
    int tableFieldPos = result.indexOf("name=\"do_table_structure\"");
    int filePos = result.indexOf("name=\"files\"");

    assertTrue(ocrFieldPos > 0, "do_ocr field should be present");
    assertTrue(tableFieldPos > 0, "do_table_structure field should be present");
    assertTrue(filePos > 0, "files field should be present");
    assertTrue(ocrFieldPos < tableFieldPos, "do_ocr should come before do_table_structure");
    assertTrue(tableFieldPos < filePos, "form fields should come before file");

    // Verify field values
    assertTrue(result.contains("do_ocr\"\r\n\r\ntrue\r\n"));
    assertTrue(result.contains("do_table_structure\"\r\n\r\nfalse\r\n"));
  }

  @Test
  void sanitizesContentTypeWithControlCharacters() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.txt",
        "text/plain\r\nX-Injected: header"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    // Should strip control characters, preventing header injection
    assertTrue(result.contains("Content-Type: text/plainX-Injected: header"));
    assertFalse(result.contains("\r\nX-Injected:"));
  }

  @Test
  void sanitizesFilenameWithControlCharacters() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test\r\n.txt",
        "text/plain"
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    // Should strip control characters from filename
    assertTrue(result.contains("filename=\"test.txt\""));
  }

  @Test
  void handlesNullContentType() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream("data".getBytes());

    MultipartInputStream multipart = new MultipartInputStream(
        "boundary",
        fileStream,
        "files",
        "test.bin",
        null
    );

    String result = new String(multipart.readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(result.contains("Content-Type: application/octet-stream"));
  }
}
