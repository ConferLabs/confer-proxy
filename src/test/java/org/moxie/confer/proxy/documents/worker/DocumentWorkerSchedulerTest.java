package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.worker.responses.DocumentExtractionMetadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWorkerSchedulerTest {

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
  void queuesUntilAConnectionIsReleased() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(2));

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      DocumentWorkerLease first = scheduler.acquire();
      Future<DocumentWorkerLease> waiting = executor.submit(scheduler::acquire);
      awaitWaiting(scheduler, 1);

      assertEquals(1, scheduler.activeConnections());
      assertEquals(1, gateway.connections());
      assertFalse(waiting.isDone());

      first.close();
      DocumentWorkerLease second = waiting.get(2, TimeUnit.SECONDS);

      assertEquals(2, gateway.connections());
      assertEquals(1, scheduler.activeConnections());
      second.close();
      assertEquals(0, scheduler.activeConnections());
    }
  }

  @Test
  void timesOutWaitingForAConnection() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofMillis(20));
    DocumentWorkerLease first = scheduler.acquire();

    DocumentWorkerTimeoutException error = assertThrows(
        DocumentWorkerTimeoutException.class,
        scheduler::acquire);

    assertEquals("Timed out waiting for a document worker", error.getMessage());
    assertEquals(0, scheduler.waitingRequests());
    assertEquals(1, scheduler.activeConnections());
    first.close();
  }

  @Test
  void failedConnectionDoesNotConsumeCapacity() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    gateway.failNextConnection();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(1));

    assertThrows(IOException.class, scheduler::acquire);
    assertEquals(0, scheduler.activeConnections());

    DocumentWorkerLease lease = scheduler.acquire();
    assertEquals(1, scheduler.activeConnections());
    lease.close();
  }

  @Test
  void closingALeaseIsIdempotent() throws Exception {
    RecordingConnection connection = new RecordingConnection();
    DocumentWorkerGateway gateway = () -> connection;
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(1));
    DocumentWorkerLease lease = scheduler.acquire();

    lease.close();
    lease.close();

    assertEquals(1, connection.closeCalls());
    assertEquals(0, scheduler.activeConnections());
  }

  @Test
  void successfulExtractionOwnsCapacityUntilItIsClosed() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(1));

    DocumentExtraction extraction = scheduler.extract(connection ->
        new DocumentExtractionResponse(
            connection,
            0,
            new DocumentExtractionMetadata(0L, 0L, 0L)));

    assertEquals(1, scheduler.activeConnections());
    extraction.close();
    assertEquals(0, scheduler.activeConnections());
  }

  @Test
  void failedExtractionReleasesCapacity() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(1));

    assertThrows(IOException.class, () -> scheduler.extract(connection -> {
      throw new IOException("Extraction failed");
    }));

    assertEquals(0, scheduler.activeConnections());
  }

  @Test
  void rejectedExtractionNeverTransfersOwnership() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(1));

    assertThrows(DocumentExtractionRejectedException.class, () ->
        scheduler.extract(connection -> {
          throw new DocumentExtractionRejectedException();
        }));

    assertEquals(0, scheduler.activeConnections());
  }

  @Test
  void servesWaitingRequestsInArrivalOrder() throws Exception {
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(gateway, 1, Duration.ofSeconds(2));
    List<Integer> admissionOrder = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      DocumentWorkerLease first = scheduler.acquire();
      List<Future<?>> waiting = new ArrayList<>();

      for (int request = 1; request <= 3; request++) {
        int requestNumber = request;
        waiting.add(executor.submit(() -> {
          try (DocumentWorkerLease ignored = scheduler.acquire()) {
            admissionOrder.add(requestNumber);
          } catch (IOException error) {
            throw new IllegalStateException(error);
          }
        }));
        awaitWaiting(scheduler, request);
      }

      first.close();
      for (Future<?> request : waiting) {
        request.get(2, TimeUnit.SECONDS);
      }
    }

    assertEquals(List.of(1, 2, 3), admissionOrder);
  }

  @Test
  void neverExceedsTheConnectionLimitDuringABurst() throws Exception {
    int maxConnections = 8;
    int requestCount   = 30;
    RecordingGateway gateway = new RecordingGateway();
    DocumentWorkerScheduler scheduler = scheduler(
        gateway,
        maxConnections,
        Duration.ofSeconds(2));
    CountDownLatch capacityReached = new CountDownLatch(maxConnections);
    CountDownLatch release         = new CountDownLatch(1);
    AtomicInteger active           = new AtomicInteger();
    AtomicInteger maximum          = new AtomicInteger();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> requests = new ArrayList<>();
      for (int request = 0; request < requestCount; request++) {
        requests.add(executor.submit(() -> {
          try (DocumentWorkerLease ignored = scheduler.acquire()) {
            int activeNow = active.incrementAndGet();
            maximum.accumulateAndGet(activeNow, Math::max);
            capacityReached.countDown();
            release.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
          } catch (IOException | InterruptedException error) {
            throw new IllegalStateException(error);
          }
        }));
      }

      awaitCapacity(scheduler, maxConnections, requestCount - maxConnections);
      assertTrue(capacityReached.await(2, TimeUnit.SECONDS));
      release.countDown();
      for (Future<?> request : requests) {
        request.get(2, TimeUnit.SECONDS);
      }
    }

    assertEquals(maxConnections, maximum.get());
    assertEquals(requestCount, gateway.connections());
    assertEquals(0, scheduler.activeConnections());
    assertEquals(0, scheduler.waitingRequests());
  }

  private static DocumentWorkerScheduler scheduler(DocumentWorkerGateway gateway,
                                                   int maxConnections,
                                                   Duration timeout)
  {
    return new DocumentWorkerScheduler(gateway, maxConnections, timeout);
  }

  private static void awaitWaiting(DocumentWorkerScheduler scheduler,
                                   int expected)
    throws InterruptedIOException
  {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (scheduler.waitingRequests() != expected) {
      if (System.nanoTime() >= deadline) {
        throw new InterruptedIOException("Document worker waiters did not reach the expected size");
      }
      Thread.onSpinWait();
    }
  }

  private static void awaitCapacity(DocumentWorkerScheduler scheduler,
                                    int active,
                                    int waiting)
    throws InterruptedIOException
  {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (scheduler.activeConnections() != active || scheduler.waitingRequests() != waiting) {
      if (System.nanoTime() >= deadline) {
        throw new InterruptedIOException("Document worker scheduler did not reach capacity");
      }
      Thread.onSpinWait();
    }
  }

  private static class RecordingGateway implements DocumentWorkerGateway {

    private final AtomicInteger connections = new AtomicInteger();

    private boolean failNextConnection;

    @Override
    public synchronized DocumentWorkerConnection connect() throws IOException {
      connections.incrementAndGet();
      if (failNextConnection) {
        failNextConnection = false;
        throw new IOException("Connection failed");
      }
      return new RecordingConnection();
    }

    private int connections() {
      return connections.get();
    }

    private synchronized void failNextConnection() {
      failNextConnection = true;
    }
  }

  private static class RecordingConnection extends DocumentWorkerConnection {

    private int closeCalls;

    private RecordingConnection() {
      super(
          new DocumentWorkerInputStream(
              new ObjectMapper(),
              new ByteArrayInputStream(new byte[0])),
          new DocumentWorkerOutputStream(
              new ObjectMapper(),
              new ByteArrayOutputStream()),
          (objectKey, encryptionKey) -> new DecryptedDocument(
              new ByteArrayInputStream(new byte[0]),
              0),
          new ObjectMapper(),
          validatorFactory.getValidator());
    }

    @Override
    public synchronized void close() {
      closeCalls++;
      super.close();
    }

    private int closeCalls() {
      return closeCalls;
    }
  }
}
