package org.moxie.confer.proxy.images;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizedImageTest {

  @Test
  void writesItsOwnPpmHeaderFromTheCheckedDimensions() throws Exception {
    NormalizedImage image = NormalizedImage.of(2, 1);

    byte[] header = "P6\n2 1\n255\n".getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(header, image.ppmHeader());
    assertEquals(6, image.pixelBytes());
    assertEquals(header.length + 6, image.ppmLength());
  }

  @Test
  void rejectsZeroDimensions() {
    assertThrows(InvalidNormalizedImageException.class, () -> NormalizedImage.of(0, 1));
    assertThrows(InvalidNormalizedImageException.class, () -> NormalizedImage.of(1, 0));
  }

  @Test
  void rejectsDimensionsBeyondThePixelBudget() {
    assertThrows(InvalidNormalizedImageException.class, () -> NormalizedImage.of(4097, 4096));
    assertThrows(InvalidNormalizedImageException.class, () -> NormalizedImage.of(0xffffffffL, 0xffffffffL));
    assertThrows(InvalidNormalizedImageException.class, () -> NormalizedImage.of(0x80000000L, 1));
  }

  @Test
  void boundsMatchTheModelsImageProcessor() {
    assertTrue(NormalizedImage.isWithinBounds(4096, 4096));
    assertFalse(NormalizedImage.isWithinBounds(4097, 4096));
    assertTrue(NormalizedImage.isWithinBounds(200, 1));
    assertFalse(NormalizedImage.isWithinBounds(201, 1));
    assertFalse(NormalizedImage.isWithinBounds(1, 201));
    assertTrue(NormalizedImage.isWithinBounds(57_800, 289));
    assertFalse(NormalizedImage.isWithinBounds(16_777_217, 1));
  }
}
