package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.DocumentNotFoundException;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;
import org.moxie.confer.proxy.documents.worker.responses.DocumentOverview;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchResult;
import org.moxie.confer.proxy.documents.worker.responses.IndexedDocumentText;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPage;
import org.moxie.confer.proxy.entities.DocumentReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "document.worker.integration", matches = "true")
class DocumentWorkerProcessIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
  private static final Path WORKER_SOURCE = PROJECT_ROOT.resolve("document-worker/src");
  private static final Path WORKER_RUNTIME = PROJECT_ROOT.resolve("target/document-worker/runtime");
  private static final String PYTHON = System.getProperty("document.worker.python", "python");
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static ValidatorFactory validatorFactory;

  @BeforeAll
  static void createValidatorFactory() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  void javaClientCompletesTheRealPythonExtractionAndRetrievalLifecycle() throws Exception {
    byte[] source = Files.readAllBytes(PROJECT_ROOT.resolve("src/test/resources/docling.pdf"));
    Map<DocumentWorkerPayloadRole, byte[]> extractionPayloads =
        new EnumMap<>(DocumentWorkerPayloadRole.class);
    ProcessDocumentWorkerGateway gateway = new ProcessDocumentWorkerGateway(emptyStorage());

    DocumentWorkerConnection extractionConnection = gateway.connect();
    DocumentWorkerRequestPayload sourcePayload = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "application/pdf",
        source.length,
        new ByteArrayInputStream(source));
    try (DocumentExtractionResponse extraction = extractionConnection.extract(
        new ExtractDocumentRequest("docling.pdf", "application/pdf", sourcePayload)))
    {
      extraction.consumePayloads((descriptor, content) ->
          extractionPayloads.put(descriptor.role(), content.readAllBytes()));
    }

    byte[] text     = extractionPayloads.get(DocumentWorkerPayloadRole.TEXT);
    byte[] artifact = extractionPayloads.get(DocumentWorkerPayloadRole.ARTIFACT);
    assertTrue(new String(text, StandardCharsets.UTF_8).contains("Docling Technical Report"));
    assertTrue(artifact.length > 0);

    String sourceKey = "objects/docling.pdf";
    Map<String, byte[]> objects = Map.of(
        sourceKey, source,
        sourceKey + ".artifact", artifact);
    DocumentStorageGateway storage = storage(objects);
    DocumentReference reference = new DocumentReference(
        "docling",
        "docling.pdf",
        "application/pdf",
        source.length,
        sourceKey,
        KEY);

    ProcessDocumentWorkerGateway sessionGateway = new ProcessDocumentWorkerGateway(storage);
    try (DocumentWorkerConnection session = sessionGateway.connect()) {
      DocumentOverview overview = session.overview(reference);
      DocumentSearchResult search = session.search(
          reference,
          List.of(new DocumentSearchQuery(List.of("Docling technical report"))),
          1_200);
      IndexedDocumentText read = session.read(reference, 0, 1);
      RenderedDocumentPage rendered = session.renderPage(reference, 0);
      session.release(reference);

      assertTrue(overview.metadata().pageCount() > 0);
      assertFalse(search.hits().isEmpty());
      assertTrue(search.hits().getFirst().text().contains("Docling"));
      assertTrue(read.text().contains("Docling Technical Report"));
      assertEquals(0, read.window().containerStart());
      assertEquals("image/png", rendered.metadata().mediaType());
      assertArrayEquals(
          new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47},
          java.util.Arrays.copyOf(rendered.png(), 4));
    }

    gateway.assertSuccessfulExits();
    sessionGateway.assertSuccessfulExits();
  }

  @Test
  void thirtyRealWorkerRequestsRespectTheJavaConnectionLimit() throws Exception {
    int requestCount   = 30;
    int maxConnections = 8;
    ProcessDocumentWorkerGateway gateway = new ProcessDocumentWorkerGateway(emptyStorage());
    DocumentWorkerScheduler scheduler = new DocumentWorkerScheduler(
        gateway,
        maxConnections,
        Duration.ofMinutes(5));

    try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> results = new ArrayList<>();
      for (int index = 0; index < requestCount; index++) {
        int requestIndex = index;
        results.add(requests.submit(() -> extractText(scheduler, requestIndex)));
      }

      for (Future<?> result : results) {
        await(result);
      }
    }

    assertEquals(0, scheduler.activeConnections());
    assertEquals(0, scheduler.waitingRequests());
    assertTrue(gateway.maximumActiveProcesses() <= maxConnections);
    assertTrue(gateway.maximumActiveProcesses() > 1);
    gateway.assertSuccessfulExits();
  }

  private void extractText(DocumentWorkerScheduler scheduler, int requestIndex) {
    byte[] source = ("queued document " + requestIndex).getBytes(StandardCharsets.UTF_8);
    DocumentWorkerRequestPayload sourcePayload = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "text/plain",
        source.length,
        new ByteArrayInputStream(source));

    try (DocumentExtraction extraction = scheduler.extract(connection -> connection.extract(
        new ExtractDocumentRequest(
            "document-" + requestIndex + ".txt",
            "text/plain",
            sourcePayload))))
    {
      extraction.writeTo(OutputStream.nullOutputStream());
    } catch (IOException error) {
      throw new IllegalStateException("Real document worker extraction failed", error);
    }
  }

  private void await(Future<?> result)
    throws InterruptedException, ExecutionException, TimeoutException
  {
    result.get(5, TimeUnit.MINUTES);
  }

  private void awaitWaitingRequests(DocumentWorkerScheduler scheduler, int expected)
    throws InterruptedException, TimeoutException
  {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (scheduler.waitingRequests() == expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new TimeoutException(
        "Expected " + expected + " queued worker requests, got " + scheduler.waitingRequests());
  }

  private DocumentStorageGateway emptyStorage() {
    return (objectKey, encryptionKey) -> {
      throw new DocumentNotFoundException();
    };
  }

  private DocumentStorageGateway storage(Map<String, byte[]> objects) {
    return (objectKey, encryptionKey) -> {
      byte[] value = objects.get(objectKey);
      if (value == null) {
        throw new DocumentNotFoundException();
      }
      return new DecryptedDocument(new ByteArrayInputStream(value), value.length);
    };
  }

  private static final class ProcessDocumentWorkerGateway implements DocumentWorkerGateway {

    private final DocumentStorageGateway storage;
    private final AtomicInteger           activeProcesses = new AtomicInteger();
    private final AtomicInteger           maximumActive   = new AtomicInteger();
    private final List<WorkerProcess>      processes       = new ArrayList<>();

    private ProcessDocumentWorkerGateway(DocumentStorageGateway storage) {
      this.storage = storage;
    }

    @Override
    public DocumentWorkerConnection connect() throws IOException {
      ProcessBuilder builder = new ProcessBuilder(
          PYTHON,
          "-S",
          "-m",
          "confer_document_worker.main");
      builder.directory(PROJECT_ROOT.toFile());
      builder.environment().put(
          "PYTHONPATH",
          WORKER_RUNTIME + System.getProperty("path.separator") + WORKER_SOURCE);

      Process process = builder.start();
      WorkerProcess worker = new WorkerProcess(process);
      synchronized (processes) {
        processes.add(worker);
      }

      int active = activeProcesses.incrementAndGet();
      maximumActive.accumulateAndGet(active, Math::max);
      return new ProcessDocumentWorkerConnection(
          process,
          activeProcesses,
          storage);
    }

    int maximumActiveProcesses() {
      return maximumActive.get();
    }

    void assertSuccessfulExits() throws Exception {
      List<WorkerProcess> snapshot;
      synchronized (processes) {
        snapshot = List.copyOf(processes);
      }
      for (WorkerProcess process : snapshot) {
        assertEquals(0, process.awaitExit(), process.stderr());
      }
    }
  }

  private static final class ProcessDocumentWorkerConnection extends DocumentWorkerConnection {

    private final Process       process;
    private final AtomicInteger activeProcesses;

    private boolean closed;

    private ProcessDocumentWorkerConnection(Process process,
                                            AtomicInteger activeProcesses,
                                            DocumentStorageGateway storage)
    {
      super(
          new DocumentWorkerInputStream(MAPPER, process.getInputStream()),
          new DocumentWorkerOutputStream(MAPPER, process.getOutputStream()),
          storage,
          MAPPER,
          validatorFactory.getValidator());
      this.process         = process;
      this.activeProcesses = activeProcesses;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        super.close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException error) {
        process.destroyForcibly();
      } finally {
        activeProcesses.decrementAndGet();
      }
    }
  }

  private static final class WorkerProcess {

    private final Process               process;
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final Thread                stderrReader;

    private WorkerProcess(Process process) {
      this.process = process;
      stderrReader = Thread.startVirtualThread(() -> copyStderr(process.getErrorStream()));
    }

    int awaitExit() throws Exception {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new TimeoutException("Document worker did not exit");
      }
      stderrReader.join(Duration.ofSeconds(5));
      return process.exitValue();
    }

    String stderr() {
      return stderr.toString(StandardCharsets.UTF_8);
    }

    private void copyStderr(InputStream input) {
      try (input) {
        input.transferTo(stderr);
      } catch (IOException ignored) {
        // Process termination can close the pipe while the diagnostic reader exits.
      }
    }
  }
}
