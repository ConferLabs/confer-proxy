package org.moxie.confer.proxy.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Wraps a file stream with multipart form-data framing for HTTP upload.
 * Produces: form fields + file part + epilogue (boundary terminator).
 */
public class MultipartInputStream extends InputStream {

  private enum State { PREAMBLE, FILE_CONTENT, EPILOGUE, DONE }

  private final InputStream fileStream;
  private final byte[] preamble;
  private final byte[] epilogue;

  private State state = State.PREAMBLE;
  private int preamblePos = 0;
  private int epiloguePos = 0;

  /**
   * A form field name-value pair.
   */
  public record FormField(String name, String value) {}

  /**
   * Create a multipart input stream wrapping the given file stream.
   *
   * @param boundary    The multipart boundary string
   * @param fileStream  The input stream containing the file data
   * @param fieldName   The form field name for the file (e.g., "file" or "files")
   * @param filename    The filename to use in Content-Disposition
   * @param contentType The MIME type of the file
   */
  public MultipartInputStream(String boundary, InputStream fileStream,
                              String fieldName, String filename, String contentType) {
    this(boundary, fileStream, fieldName, filename, contentType, List.of());
  }

  /**
   * Create a multipart input stream with additional form fields.
   *
   * @param boundary    The multipart boundary string
   * @param fileStream  The input stream containing the file data
   * @param fieldName   The form field name for the file (e.g., "file" or "files")
   * @param filename    The filename to use in Content-Disposition
   * @param contentType The MIME type of the file
   * @param formFields  Additional form fields to include before the file
   */
  public MultipartInputStream(String boundary, InputStream fileStream,
                              String fieldName, String filename, String contentType,
                              List<FormField> formFields) {
    this.fileStream = fileStream;

    StringBuilder preambleBuilder = new StringBuilder();

    // Add form fields first
    for (FormField field : formFields) {
      preambleBuilder.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(field.name()).append("\"\r\n")
          .append("\r\n")
          .append(field.value()).append("\r\n");
    }

    // Add file part
    preambleBuilder.append("--").append(boundary).append("\r\n")
        .append("Content-Disposition: form-data; name=\"").append(fieldName).append("\"; filename=\"")
        .append(escapeFilename(filename)).append("\"\r\n")
        .append("Content-Type: ").append(sanitizeContentType(contentType)).append("\r\n")
        .append("\r\n");

    this.preamble = preambleBuilder.toString().getBytes(StandardCharsets.UTF_8);
    this.epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Sanitize content type to prevent header injection.
   * Removes any control characters including CR/LF.
   */
  private String sanitizeContentType(String contentType) {
    if (contentType == null) {
      return "application/octet-stream";
    }
    StringBuilder sb = new StringBuilder(contentType.length());
    for (int i = 0; i < contentType.length(); i++) {
      char c = contentType.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        continue;
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Escape special characters in filename for Content-Disposition header.
   * Removes control characters and escapes quotes/backslashes to prevent header injection.
   */
  private String escapeFilename(String filename) {
    StringBuilder sb = new StringBuilder(filename.length());
    for (int i = 0; i < filename.length(); i++) {
      char c = filename.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        continue;
      }
      if (c == '\\') {
        sb.append("\\\\");
      } else if (c == '"') {
        sb.append("\\\"");
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  @Override
  public int read() throws IOException {
    byte[] b = new byte[1];
    int n = read(b, 0, 1);
    return n == -1 ? -1 : b[0] & 0xFF;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    switch (state) {
      case PREAMBLE:
        int preambleRemaining = preamble.length - preamblePos;
        if (preambleRemaining > 0) {
          int toCopy = Math.min(len, preambleRemaining);
          System.arraycopy(preamble, preamblePos, b, off, toCopy);
          preamblePos += toCopy;
          return toCopy;
        }
        state = State.FILE_CONTENT;
        // fall through

      case FILE_CONTENT:
        int n = fileStream.read(b, off, len);
        if (n == -1) {
          state = State.EPILOGUE;
          return read(b, off, len);
        }
        return n;

      case EPILOGUE:
        int epilogueRemaining = epilogue.length - epiloguePos;
        if (epilogueRemaining > 0) {
          int toCopy = Math.min(len, epilogueRemaining);
          System.arraycopy(epilogue, epiloguePos, b, off, toCopy);
          epiloguePos += toCopy;
          return toCopy;
        }
        state = State.DONE;
        return -1;

      case DONE:
      default:
        return -1;
    }
  }

  @Override
  public void close() throws IOException {
    fileStream.close();
  }
}
