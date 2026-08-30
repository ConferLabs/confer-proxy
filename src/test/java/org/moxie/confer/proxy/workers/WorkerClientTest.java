package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.auth.JWT;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.producers.ObjectMapperProducer;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkerClientTest {

  private static final URI CONTROLLER_URI = URI.create(
      "wss://controller.test/v1/worker");
  private static final String SUBJECT           = "generated-attachments";
  private static final String TOKEN             = "worker-token";
  private static final String REPLACEMENT_TOKEN = "replacement-worker-token";

  @Test
  void productionConstructorBuildsTheCompleteAttestationClient() {
    Config config = mock(Config.class);
    when(config.getWorkerControllerUri()).thenReturn(CONTROLLER_URI.toString());

    assertDoesNotThrow(
        () -> new WorkerClient(
            new ObjectMapperProducer().produceObjectMapper(),
            config,
            mock(AttachmentPublisher.class)));
  }

  @Test
  void createsAWorkspaceWithoutOpeningAConnection() {
    try (Fixture fixture = new Fixture()) {
      WorkerWorkspace workspace = fixture.client.getWorkspace(SUBJECT);

      verifyNoInteractions(fixture.webSockets, fixture.attestation);
      assertTrue(fixture.connections.constructed().isEmpty());

      workspace.close();
    }
  }

  @Test
  void opensOneConnectionAndOwnsItUntilClose() throws Exception {
    try (Fixture fixture = new Fixture()) {
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);

      WorkerConnection connection = session.getConnection();
      assertSame(connection, session.getConnection());

      verify(connection, times(1)).connect(
          same(fixture.webSockets),
          same(CONTROLLER_URI),
          same(TOKEN));

      session.close();
      session.close();
      verify(connection, times(1)).close();
    }
  }

  @Test
  void replacesAClosedConnection() throws Exception {
    try (Fixture fixture = new Fixture()) {
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);
      WorkerConnection first = session.getConnection();
      when(first.isOpen()).thenReturn(false);

      WorkerConnection replacement = session.getConnection();

      assertNotSame(first, replacement);
      assertEquals(2, fixture.connections.constructed().size());
      verify(first).close();
      assertSame(replacement, session.getConnection());
      session.close();
      verify(replacement).close();
    }
  }

  @Test
  void refreshesTheControllerTokenForAReplacementConnection() throws Exception {
    try (Fixture fixture = new Fixture()) {
      when(fixture.jwt.generate(SUBJECT))
          .thenReturn(TOKEN, REPLACEMENT_TOKEN);
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);
      WorkerConnection first = session.getConnection();
      when(first.isOpen()).thenReturn(false);

      WorkerConnection replacement = session.getConnection();

      verify(first).connect(
          same(fixture.webSockets),
          same(CONTROLLER_URI),
          same(TOKEN));
      verify(replacement).connect(
          same(fixture.webSockets),
          same(CONTROLLER_URI),
          same(REPLACEMENT_TOKEN));
      verify(fixture.jwt, times(2)).generate(SUBJECT);
      session.close();
    }
  }

  @Test
  void doesNotExposeAReplacementUntilItIsEstablished() throws Exception {
    try (Fixture fixture = new Fixture()) {
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);
      WorkerConnection first = session.getConnection();
      when(first.isOpen()).thenReturn(false);
      CountDownLatch secondStarted = new CountDownLatch(1);
      CountDownLatch secondReturned = new CountDownLatch(1);
      AtomicReference<WorkerConnection> secondConnection = new AtomicReference<>();
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      AtomicReference<Thread> secondThread = new AtomicReference<>();
      fixture.connectAction = () -> {
        secondThread.set(Thread.ofPlatform().start(() -> {
          secondStarted.countDown();
          try {
            secondConnection.set(session.getConnection());
          } catch (WorkerException error) {
            secondFailure.set(error);
          } finally {
            secondReturned.countDown();
          }
        }));
        try {
          assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
          awaitBlocked(secondThread.get());
          assertEquals(1, secondReturned.getCount());
        } catch (InterruptedException error) {
          throw new AssertionError(error);
        }
      };

      WorkerConnection replacement = session.getConnection();

      assertTrue(secondReturned.await(1, TimeUnit.SECONDS));
      secondThread.get().join(TimeUnit.SECONDS.toMillis(1));
      assertFalse(secondThread.get().isAlive());
      assertSame(replacement, secondConnection.get());
      assertNull(secondFailure.get());
      assertEquals(2, fixture.connections.constructed().size());
      verify(first).close();
      verify(replacement).connect(
          same(fixture.webSockets),
          same(CONTROLLER_URI),
          same(TOKEN));
      session.close();
    }
  }

  @Test
  void doesNotExposeAConnectionUntilItIsEstablished() throws Exception {
    try (Fixture fixture = new Fixture()) {
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);
      CountDownLatch secondStarted = new CountDownLatch(1);
      CountDownLatch secondReturned = new CountDownLatch(1);
      AtomicReference<WorkerConnection> secondConnection = new AtomicReference<>();
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      AtomicReference<Thread> secondThread = new AtomicReference<>();
      fixture.connectAction = () -> {
        secondThread.set(Thread.ofPlatform().start(() -> {
          secondStarted.countDown();
          try {
            secondConnection.set(session.getConnection());
          } catch (WorkerException error) {
            secondFailure.set(error);
          } finally {
            secondReturned.countDown();
          }
        }));
        try {
          assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
          awaitBlocked(secondThread.get());
          assertEquals(1, secondReturned.getCount());
        } catch (InterruptedException error) {
          throw new AssertionError(error);
        }
      };

      WorkerConnection connection = session.getConnection();

      assertTrue(secondReturned.await(1, TimeUnit.SECONDS));
      secondThread.get().join(TimeUnit.SECONDS.toMillis(1));
      assertFalse(secondThread.get().isAlive());
      assertSame(connection, secondConnection.get());
      assertNull(secondFailure.get());
      verify(connection, times(1)).connect(
          same(fixture.webSockets),
          same(CONTROLLER_URI),
          same(TOKEN));
      session.close();
    }
  }

  @Test
  void releasesFailedConnectionsAndAllowsAnotherAttempt() throws Exception {
    try (Fixture fixture = new Fixture()) {
      WorkerException rejection = new WorkerException("untrusted worker");
      fixture.connectionFailure = rejection;
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);

      assertSame(rejection, assertThrows(
          WorkerException.class,
          session::getConnection));
      WorkerConnection connection = session.getConnection();

      assertEquals(2, fixture.connections.constructed().size());
      verify(fixture.connections.constructed().getFirst()).close();
      session.close();
      verify(connection).close();
    }
  }

  @Test
  void releasesConnectionsAfterUnexpectedFailures() throws Exception {
    try (Fixture fixture = new Fixture()) {
      IllegalStateException unexpected = new IllegalStateException("bug");
      fixture.unexpectedFailure = unexpected;
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);

      assertSame(unexpected, assertThrows(
          IllegalStateException.class,
          session::getConnection));
      WorkerConnection connection = session.getConnection();

      verify(fixture.connections.constructed().getFirst()).close();
      session.close();
      verify(connection).close();
    }
  }

  @Test
  void closingTheSessionFromAnotherThreadCancelsConnectionEstablishment()
      throws Exception
  {
    try (Fixture fixture = new Fixture()) {
      WorkerClient.Session session = fixture.client.new Session(SUBJECT);
      AtomicReference<Thread> closing = new AtomicReference<>();
      fixture.connectAction = () -> {
        closing.set(Thread.ofVirtual().start(session::close));
        try {
          closing.get().join(TimeUnit.SECONDS.toMillis(1));
          assertFalse(closing.get().isAlive());
        } catch (InterruptedException error) {
          throw new AssertionError(error);
        }
      };

      WorkerException failure = assertThrows(
          WorkerException.class,
          session::getConnection);

      assertFalse(closing.get().isAlive());
      assertEquals(
          "Worker connection attempt was cancelled",
          failure.getMessage());
      verify(fixture.connections.constructed().getFirst()).close();
      assertThrows(WorkerException.class, session::getConnection);
    }
  }

  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.BLOCKED, thread.getState());
  }

  private static class Fixture implements AutoCloseable {

    private final WebSocketContainer webSockets = mock(WebSocketContainer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JWT jwt = mock(JWT.class);
    private final WorkerAttestationHandshake attestation =
        mock(WorkerAttestationHandshake.class);
    private final AttachmentPublisher attachments = mock(AttachmentPublisher.class);

    private WorkerException  connectionFailure;
    private RuntimeException unexpectedFailure;
    private Runnable         connectAction = () -> {};
    private final MockedConstruction<WorkerConnection> connections = mockConstruction(
        WorkerConnection.class,
        (connection, context) -> {
          assertSame(mapper, context.arguments().get(0));
          assertSame(attestation, context.arguments().get(1));
          when(connection.isOpen()).thenReturn(true);
          doAnswer(ignored -> {
            connectAction.run();
            if (connectionFailure != null) {
              WorkerException failure = connectionFailure;
              connectionFailure = null;
              throw failure;
            }
            if (unexpectedFailure != null) {
              RuntimeException failure = unexpectedFailure;
              unexpectedFailure = null;
              throw failure;
            }
            return null;
          }).when(connection).connect(
              same(webSockets),
              same(CONTROLLER_URI),
              anyString());
        });
    private final WorkerClient client = new WorkerClient(
        webSockets,
        CONTROLLER_URI,
        mapper,
        jwt,
        attestation,
        attachments);

    private Fixture() {
      when(jwt.generate(SUBJECT)).thenReturn(TOKEN);
    }

    @Override
    public void close() {
      connections.close();
    }
  }
}
