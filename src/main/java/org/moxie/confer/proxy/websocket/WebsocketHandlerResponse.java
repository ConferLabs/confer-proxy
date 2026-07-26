package org.moxie.confer.proxy.websocket;

import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public sealed interface WebsocketHandlerResponse
    extends ManagedResource
    permits WebsocketHandlerResponse.SingleResponse, WebsocketHandlerResponse.StreamingResponse
{

  @Override
  default void close() {}

  record SingleResponse(int statusCode, String body) implements WebsocketHandlerResponse {}

  final class StreamingResponse implements WebsocketHandlerResponse {

    private static final ManagedResource NO_RESOURCE = () -> {};

    private final Map<String, String> headers;
    private final StreamingOutput    stream;
    private final ManagedResource     resource;

    public StreamingResponse(StreamingOutput stream) {
      this(Map.of(), stream);
    }

    public StreamingResponse(Map<String, String> headers,
                             StreamingOutput stream)
    {
      this(headers, stream, NO_RESOURCE);
    }

    private StreamingResponse(Map<String, String> headers,
                              StreamingOutput stream,
                              ManagedResource resource)
    {
      this.headers  = Map.copyOf(Objects.requireNonNull(headers, "headers"));
      this.stream   = Objects.requireNonNull(stream, "stream");
      this.resource = Objects.requireNonNull(resource, "resource");
    }

    public static <Resource extends ManagedResource, Failure extends Exception>
    StreamingResponse using(
        ResourceFactory<Resource, Failure> resources,
        ResourceStreamingOutput<Resource> stream)
      throws Failure
    {
      return using(resources, ignored -> Map.of(), stream);
    }

    public static <Resource extends ManagedResource, Failure extends Exception>
    StreamingResponse using(
        ResourceFactory<Resource, Failure> resources,
        Function<Resource, Map<String, String>> headers,
        ResourceStreamingOutput<Resource> stream)
      throws Failure
    {
      Objects.requireNonNull(resources, "resources");
      Objects.requireNonNull(headers, "headers");
      Objects.requireNonNull(stream, "stream");

      Resource resource    = null;
      boolean  transferred = false;
      try {
        resource = resources.open();
        Resource ownedResource = Objects.requireNonNull(resource, "resource");
        StreamingResponse response = new StreamingResponse(
            headers.apply(ownedResource),
            output -> stream.write(ownedResource, output),
            ownedResource);
        transferred = true;
        return response;
      } finally {
        if (!transferred && resource != null) {
          resource.close();
        }
      }
    }

    public Map<String, String> headers() {
      return headers;
    }

    public void writeTo(OutputStream output) throws IOException {
      stream.write(output);
    }

    @Override
    public void close() {
      resource.close();
    }
  }
}
