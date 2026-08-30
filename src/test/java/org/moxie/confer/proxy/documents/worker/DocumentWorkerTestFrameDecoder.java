package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DocumentWorkerTestFrameDecoder {

  private static final TypeReference<Map<String, Object>> HEADER_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<DocumentWorkerPayloadDescriptor>> PAYLOAD_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper    mapper;
  private final DataInputStream input;

  public DocumentWorkerTestFrameDecoder(ObjectMapper mapper, InputStream input) {
    this.mapper = mapper;
    this.input  = new DataInputStream(input);
  }

  public DocumentWorkerTestFrame read() throws IOException {
    int headerLength = input.readInt();
    byte[] encodedHeader = input.readNBytes(headerLength);
    if (encodedHeader.length != headerLength) {
      throw new IOException("Test frame header is truncated");
    }

    Map<String, Object> header = mapper.readValue(encodedHeader, HEADER_TYPE);
    List<DocumentWorkerPayloadDescriptor> descriptors = mapper.convertValue(
        header.remove("payloads"),
        PAYLOAD_TYPE);
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads =
        new LinkedHashMap<>();

    for (DocumentWorkerPayloadDescriptor descriptor : descriptors) {
      byte[] content = input.readNBytes(Math.toIntExact(descriptor.length()));
      if (content.length != descriptor.length()) {
        throw new IOException("Test frame payload is truncated");
      }
      payloads.put(
          descriptor.role(),
          new DocumentWorkerResponsePayload(descriptor.mediaType(), content));
    }

    return new DocumentWorkerTestFrame(Map.copyOf(header), Map.copyOf(payloads));
  }
}
