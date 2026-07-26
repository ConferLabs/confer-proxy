package org.moxie.confer.proxy.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageKeyTest {

  @Test
  void preservesAnOpaqueNamespace() throws Exception {
    ObjectStorageKey key = new ObjectStorageKey("opaque-namespace-7Kq/document.pdf.artifact");

    assertEquals("opaque-namespace-7Kq/document.pdf.artifact", key.value());
  }

  @Test
  void rejectsTraversalAndUrlLikeKeys() {
    assertThrows(InvalidObjectStorageKeyException.class,
        () -> new ObjectStorageKey("../document"));
    assertThrows(InvalidObjectStorageKeyException.class,
        () -> new ObjectStorageKey("https://example.com/document"));
    assertThrows(InvalidObjectStorageKeyException.class,
        () -> new ObjectStorageKey("/absolute/document"));
  }
}
