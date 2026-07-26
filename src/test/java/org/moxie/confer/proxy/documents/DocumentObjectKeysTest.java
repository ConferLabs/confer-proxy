package org.moxie.confer.proxy.documents;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentObjectKeysTest {

  @Test
  void derivesTextAndArtifactKeysFromOneCanonicalSource() throws Exception {
    DocumentObjectKeys keys = new DocumentObjectKeys("opaque/attachment");

    assertEquals("opaque/attachment", keys.source());
    assertEquals("opaque/attachment.txt", keys.text());
    assertEquals("opaque/attachment.artifact", keys.artifact());
  }

  @Test
  void rejectsDerivedAndUnsafeSourceKeys() {
    assertThrows(
        InvalidObjectStorageKeyException.class,
        () -> new DocumentObjectKeys("opaque/attachment.txt"));
    assertThrows(
        InvalidObjectStorageKeyException.class,
        () -> new DocumentObjectKeys("opaque/attachment.artifact"));
    assertThrows(
        InvalidObjectStorageKeyException.class,
        () -> new DocumentObjectKeys("../attachment"));
  }
}
