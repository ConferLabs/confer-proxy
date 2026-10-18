package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextWindowTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void readsAWindowFromAWorkerThatDoesNotReportTruncation() throws Exception {
    DocumentTextWindow window = MAPPER.readValue(
        "{\"container_start\":0,\"container_end\":0,\"total_containers\":1,\"regions\":[],\"containers\":[]}",
        DocumentTextWindow.class);

    assertFalse(window.truncated());
  }

  @Test
  void readsATruncatedWindow() throws Exception {
    DocumentTextWindow window = MAPPER.readValue(
        "{\"container_start\":0,\"container_end\":0,\"total_containers\":1,\"regions\":[],\"containers\":[],\"truncated\":true}",
        DocumentTextWindow.class);

    assertTrue(window.truncated());
  }
}
