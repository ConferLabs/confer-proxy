package org.moxie.confer.proxy.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutionContextTest {

  @Test
  void requiresDocuments() {
    assertThrows(NullPointerException.class, () -> new ToolExecutionContext(null));
  }
}
