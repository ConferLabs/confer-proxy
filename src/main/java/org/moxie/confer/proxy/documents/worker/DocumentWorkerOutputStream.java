package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestHeader;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseHeader;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DocumentWorkerOutputStream extends OutputStream {

  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private final ObjectMapper     mapper;
  private final DataOutputStream output;

  private boolean closed;

  public DocumentWorkerOutputStream(ObjectMapper mapper, OutputStream output) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.output = new DataOutputStream(Objects.requireNonNull(output, "output"));
  }

  public synchronized void writeRequest(DocumentWorkerRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    request.validate();
    List<DocumentWorkerRequestPayload> payloads = request.payloads();
    writeEncodedFrame(
        new DocumentWorkerRequestHeader(
            DocumentWorkerRequest.VERSION,
            request.operation().wireName(),
            request,
            descriptors(payloads)),
        payloads);
  }

  public synchronized void writeResponse(DocumentWorkerResponse<?> response) throws IOException {
    Objects.requireNonNull(response, "response");
    List<DocumentWorkerRequestPayload> payloads = new ArrayList<>();

    for (Map.Entry<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> entry
        : response.payloads().entrySet())
    {
      DocumentWorkerResponsePayload payload = Objects.requireNonNull(
          entry.getValue(),
          "payload");
      byte[] content = Objects.requireNonNull(payload.content(), "payload content");

      payloads.add(new DocumentWorkerRequestPayload(
          entry.getKey(),
          payload.mediaType(),
          content.length,
          new ByteArrayInputStream(content)));
    }

    List<DocumentWorkerPayloadDescriptor> descriptors = descriptors(payloads);
    writeEncodedFrame(
        new DocumentWorkerResponseHeader<>(
            DocumentWorkerRequest.VERSION,
            response.status(),
            response.result(),
            response.error(),
            descriptors),
        payloads);
  }

  @Override
  public synchronized void write(int value) throws IOException {
    requireOpen();
    output.write(value);
  }

  @Override
  public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
    requireOpen();
    output.write(buffer, offset, length);
  }

  @Override
  public synchronized void flush() throws IOException {
    requireOpen();
    output.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    output.close();
  }

  private void writeEncodedFrame(Object header,
                                 List<DocumentWorkerRequestPayload> payloads)
    throws IOException
  {
    requireOpen();
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(payloads, "payloads");

    byte[] encodedHeader = mapper.writeValueAsBytes(header);
    if (encodedHeader.length > DocumentWorkerFrame.MAX_HEADER_BYTES) {
      throw new DocumentWorkerProtocolException(
          "Document worker protocol header exceeds maximum size");
    }

    output.writeInt(encodedHeader.length);
    output.write(encodedHeader);
    for (DocumentWorkerRequestPayload payload : payloads) {
      transferExactly(payload.input(), output, payload.length());
    }
    output.flush();
  }

  private List<DocumentWorkerPayloadDescriptor> descriptors(
      List<DocumentWorkerRequestPayload> payloads)
    throws DocumentWorkerProtocolException
  {
    Objects.requireNonNull(payloads, "payloads");
    List<DocumentWorkerPayloadDescriptor> descriptors = new ArrayList<>();

    for (DocumentWorkerRequestPayload payload : payloads) {
      if (payload == null) {
        throw new DocumentWorkerProtocolException("Document worker request payload is missing");
      }

      payload.validate();
      descriptors.add(new DocumentWorkerPayloadDescriptor(
          payload.role(),
          payload.mediaType(),
          payload.length()));
    }

    return new DocumentWorkerPayloadManifest(descriptors).descriptors();
  }

  private static void transferExactly(InputStream input, OutputStream output, long length)
    throws IOException
  {
    byte[] buffer    = new byte[COPY_BUFFER_BYTES];
    long   remaining = length;

    while (remaining > 0) {
      int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) {
        throw new IOException("Document worker payload ended before its declared length");
      }
      output.write(buffer, 0, read);
      remaining -= read;
    }
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Document worker output stream is closed");
    }
  }
}
