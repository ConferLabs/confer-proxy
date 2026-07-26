package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseHeader;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class DocumentWorkerInputStream extends InputStream {

  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private final ObjectMapper    mapper;
  private final DataInputStream input;

  private DocumentWorkerFrame<?> currentResponse;
  private long                   currentBytesRead;
  private long                   currentBytesRemaining;
  private boolean                closed;

  public DocumentWorkerInputStream(ObjectMapper mapper, InputStream input) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.input  = new DataInputStream(Objects.requireNonNull(input, "input"));
  }

  public synchronized DocumentWorkerFrame<Void> beginResponse() throws IOException {
    return beginResponse(Void.class);
  }

  public synchronized <T> DocumentWorkerFrame<T> beginResponse(Class<T> resultType)
    throws IOException
  {
    requireOpen();
    requireCurrentResponseConsumed();

    DocumentWorkerFrame<T> response = readFrame(resultType);
    currentResponse       = response;
    currentBytesRead      = 0;
    currentBytesRemaining = currentResponse.payloadBytes();

    return response;
  }

  public synchronized DocumentWorkerResponse<Void> readResponse() throws IOException {
    return readResponse(Void.class);
  }

  public synchronized <T> DocumentWorkerResponse<T> readResponse(Class<T> resultType)
    throws IOException
  {
    DocumentWorkerFrame<T> response = beginResponse(resultType);
    return readCurrentResponse(response);
  }

  public synchronized DocumentWorkerResponse<?> readCurrentResponse() throws IOException {
    return readCurrentResponse(requireUntouchedCurrentResponse());
  }

  private <T> DocumentWorkerResponse<T> readCurrentResponse(DocumentWorkerFrame<T> response)
    throws IOException
  {
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads = new LinkedHashMap<>();

    for (DocumentWorkerPayloadDescriptor payload : response.payloads()) {
      byte[] content = readNBytes(Math.toIntExact(payload.length()));

      if (content.length != payload.length()) {
        throw new IOException("Document worker response payload is truncated");
      }

      payloads.put(payload.role(), new DocumentWorkerResponsePayload(payload.mediaType(), content));
    }

    completeCurrentResponse();

    return response.response(payloads);
  }

  synchronized void writeCurrentPayloadTo(DocumentWorkerPayloadRole role,
                                          OutputStream output)
    throws IOException
  {
    DocumentWorkerFrame<?> response = requireUntouchedCurrentResponse();
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(output, "output");

    boolean found = false;
    byte[]  buffer = new byte[COPY_BUFFER_BYTES];
    for (DocumentWorkerPayloadDescriptor payload : response.payloads()) {
      if (payload.role() == role) {
        transfer(payload.length(), output, buffer);
        found = true;
      } else {
        transfer(payload.length(), OutputStream.nullOutputStream(), buffer);
      }
    }

    completeCurrentResponse();
    if (!found) {
      throw new IOException("Document worker response is missing the required payload");
    }
  }

  synchronized void consumeCurrentPayload(DocumentWorkerPayloadRole role,
                                          DocumentWorkerPayloadHandler handler)
    throws IOException
  {
    DocumentWorkerFrame<?> response = requireUntouchedCurrentResponse();
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(handler, "handler");

    boolean found = false;
    for (DocumentWorkerPayloadDescriptor payload : response.payloads()) {
      DocumentWorkerPayloadInputStream content = new DocumentWorkerPayloadInputStream(
          input,
          payload.length());
      try {
        if (payload.role() == role) {
          handler.handle(payload, content);
          found = true;
        } else {
          content.transferTo(OutputStream.nullOutputStream());
        }
      } finally {
        long consumed = content.consumedBytes();
        currentBytesRead      += consumed;
        currentBytesRemaining -= consumed;
        content.invalidate();
      }
      if (!content.fullyConsumed()) {
        throw new IOException("Document worker response payload was not fully consumed");
      }
    }

    completeCurrentResponse();
    if (!found) {
      throw new IOException("Document worker response is missing the required payload");
    }
  }

  synchronized void consumeCurrentPayloads(DocumentWorkerPayloadHandler handler)
    throws IOException
  {
    DocumentWorkerFrame<?> response = requireUntouchedCurrentResponse();
    Objects.requireNonNull(handler, "handler");

    for (DocumentWorkerPayloadDescriptor descriptor : response.payloads()) {
      DocumentWorkerPayloadInputStream content = new DocumentWorkerPayloadInputStream(
          input,
          descriptor.length());
      try {
        handler.handle(descriptor, content);
      } finally {
        long consumed = content.consumedBytes();
        currentBytesRead      += consumed;
        currentBytesRemaining -= consumed;
        content.invalidate();
      }
      if (!content.fullyConsumed()) {
        throw new IOException("Document worker response payload was not fully consumed");
      }
    }

    completeCurrentResponse();
  }

  public synchronized void writeCurrentResponseTo(OutputStream output) throws IOException {
    DocumentWorkerFrame<?> response = requireUntouchedCurrentResponse();
    Objects.requireNonNull(output, "output");

    DataOutputStream framed = new DataOutputStream(output);
    byte[]           encodedHeader = response.encodedHeader();
    framed.writeInt(encodedHeader.length);
    framed.write(encodedHeader);

    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    while (currentBytesRemaining > 0) {
      int read = read(buffer, 0, (int) Math.min(buffer.length, currentBytesRemaining));
      if (read < 0) {
        throw new IOException("Document worker response payload is truncated");
      }
      framed.write(buffer, 0, read);
    }

    framed.flush();
    completeCurrentResponse();
  }

  @Override
  public synchronized int read() throws IOException {
    byte[] singleByte = new byte[1];
    int read = read(singleByte, 0, 1);
    return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
  }

  @Override
  public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    requireOpen();

    if (length == 0) {
      return 0;
    }

    if (currentResponse == null || currentBytesRemaining == 0) {
      return -1;
    }

    int read = input.read(buffer, offset, (int) Math.min(length, currentBytesRemaining));
    if (read < 0) {
      throw new IOException("Document worker response payload is truncated");
    }

    currentBytesRead += read;
    currentBytesRemaining -= read;

    return read;
  }

  @Override
  public synchronized int available() throws IOException {
    requireOpen();
    return (int) Math.min(input.available(), currentBytesRemaining);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }

    closed                = true;
    currentResponse       = null;
    currentBytesRead      = 0;
    currentBytesRemaining = 0;
    input.close();
  }

  private DocumentWorkerFrame<?> requireUntouchedCurrentResponse() throws IOException {
    requireOpen();

    if (currentResponse == null) {
      throw new IOException("No document worker response is open");
    }

    if (currentBytesRead != 0) {
      throw new IOException("Document worker response payload was already partially consumed");
    }

    return currentResponse;
  }

  private <T> DocumentWorkerFrame<T> readFrame(Class<T> resultType) throws IOException {
    int headerLength;
    try {
      headerLength = input.readInt();
    } catch (EOFException error) {
      throw new IOException("Document worker closed without a response", error);
    }
    if (headerLength < 2 || headerLength > DocumentWorkerFrame.MAX_HEADER_BYTES) {
      throw new DocumentWorkerProtocolException(
          "Document worker response header length is invalid");
    }

    byte[] encodedHeader = input.readNBytes(headerLength);
    if (encodedHeader.length != headerLength) {
      throw new DocumentWorkerProtocolException(
          "Document worker response header is truncated");
    }

    DocumentWorkerResponseHeader<T> header;
    try {
      JavaType headerType = mapper.getTypeFactory().constructParametricType(
          DocumentWorkerResponseHeader.class,
          resultType);
      header = mapper.readValue(encodedHeader, headerType);
    } catch (IOException | IllegalArgumentException error) {
      throw new DocumentWorkerProtocolException(
          "Document worker response header is invalid",
          error);
    }

    validate(header);
    return new DocumentWorkerFrame<>(header, encodedHeader);
  }

  private void validate(DocumentWorkerResponseHeader<?> header)
    throws DocumentWorkerProtocolException
  {
    if (header == null
        || !Integer.valueOf(DocumentWorkerRequest.VERSION).equals(header.version()))
    {
      throw new DocumentWorkerProtocolException(
          "Document worker protocol version is unsupported");
    }
    if (header.status() == null) {
      throw new DocumentWorkerProtocolException(
          "Document worker response status is missing");
    }
    if (header.status() == DocumentWorkerResponseStatus.ERROR
        && (header.error() == null
            || header.error().code() == null
            || header.error().code().isBlank()
            || header.error().message() == null
            || header.error().message().isBlank()))
    {
      throw new DocumentWorkerProtocolException(
          "Document worker response error is invalid");
    }
    if (header.status() == DocumentWorkerResponseStatus.OK && header.error() != null) {
      throw new DocumentWorkerProtocolException(
          "Successful document worker response contains an error");
    }
  }

  private void requireCurrentResponseConsumed() throws IOException {
    if (currentResponse != null && currentBytesRemaining > 0) {
      throw new IOException("Document worker response payload was not fully consumed");
    }

    currentResponse       = null;
    currentBytesRead      = 0;
    currentBytesRemaining = 0;
  }

  private void completeCurrentResponse() {
    currentResponse       = null;
    currentBytesRead      = 0;
    currentBytesRemaining = 0;
  }

  private void transfer(long length, OutputStream output, byte[] buffer) throws IOException {
    long remaining = length;
    while (remaining > 0) {
      int read = read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) {
        throw new IOException("Document worker response payload is truncated");
      }
      output.write(buffer, 0, read);
      remaining -= read;
    }
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Document worker input stream is closed");
    }
  }
}
