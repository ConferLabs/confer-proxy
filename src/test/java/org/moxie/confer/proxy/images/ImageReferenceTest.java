package org.moxie.confer.proxy.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageReferenceTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void acceptsEncryptedImageCapability() {
    assertDoesNotThrow(() -> new ImageReference("temporary-images/image", KEY, "image/png"));
  }

  @Test
  void rejectsMalformedCapabilities() {
    assertThrows(InvalidImageReferenceException.class,
        () -> new ImageReference("../image", KEY, "image/png"));
    assertThrows(InvalidImageReferenceException.class,
        () -> new ImageReference("temporary-images/image", "too-short", "image/png"));
    assertThrows(InvalidImageReferenceException.class,
        () -> new ImageReference("temporary-images/image", KEY, "text/plain"));
    assertThrows(InvalidImageReferenceException.class,
        () -> new ImageReference("temporary-images/image", KEY, "image/png\r\nX-Test: value"));
  }

  @Test
  void roundTripsThroughJackson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ImageReference expected = new ImageReference("temporary-images/image", KEY, "image/png");

    ImageReference actual = mapper.readValue(mapper.writeValueAsBytes(expected), ImageReference.class);

    assertEquals(expected, actual);
  }
}
