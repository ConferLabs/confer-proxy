package org.moxie.confer.proxy.images;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePoolKeyTest {

  @Test
  void generatesDistinctWellFormedKeys() {
    String first  = ImagePoolKey.generate();
    String second = ImagePoolKey.generate();

    assertTrue(ImagePoolKey.isWellFormed(first));
    assertTrue(ImagePoolKey.isWellFormed(second));
    assertNotEquals(first, second);
  }

  @Test
  void rejectsMalformedKeys() {
    assertFalse(ImagePoolKey.isWellFormed(null));
    assertFalse(ImagePoolKey.isWellFormed(""));
    assertFalse(ImagePoolKey.isWellFormed("A".repeat(32)));
    assertFalse(ImagePoolKey.isWellFormed("a".repeat(31)));
    assertFalse(ImagePoolKey.isWellFormed("a".repeat(33)));
    assertFalse(ImagePoolKey.isWellFormed("../" + "a".repeat(29)));
  }
}
