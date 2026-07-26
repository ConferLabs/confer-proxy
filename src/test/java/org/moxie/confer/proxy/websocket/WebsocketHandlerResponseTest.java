package org.moxie.confer.proxy.websocket;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsocketHandlerResponseTest {

  @Test
  void closesResourceWhenResponseConstructionFails() {
    AtomicBoolean closed = new AtomicBoolean();
    ManagedResource resource = () -> closed.set(true);

    assertThrows(IllegalStateException.class, () ->
        WebsocketHandlerResponse.StreamingResponse.using(
            () -> resource,
            ignored -> {
              throw new IllegalStateException("Header construction failed");
            },
            (ignored, output) -> output.write(1)));

    assertTrue(closed.get());
  }

  @Test
  void transfersResourceOwnershipToResponse() throws Exception {
    AtomicBoolean closed = new AtomicBoolean();
    ManagedResource resource = () -> closed.set(true);

    try (WebsocketHandlerResponse.StreamingResponse response =
             WebsocketHandlerResponse.StreamingResponse.using(
                 () -> resource,
                 ignored -> Map.of("Content-Type", "application/octet-stream"),
                 (ignored, output) -> output.write(1)))
    {
      assertFalse(closed.get());
      response.writeTo(new ByteArrayOutputStream());
      assertFalse(closed.get());
    }
    assertTrue(closed.get());
  }
}
