package org.moxie.confer.proxy.websocket;

import com.southernstorm.noise.protocol.CipherState;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.ShortBufferException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoiseConnectionWebsocketTest {

  @Mock
  private Session session;

  @Mock
  private RemoteEndpoint.Basic basicRemote;

  @Mock
  private CipherState mockCipher;

  private TestableNoiseConnectionWebsocket websocket;
  private List<ByteBuffer> sentMessages;

  @BeforeEach
  void setUp() throws Exception {
    sentMessages = Collections.synchronizedList(new ArrayList<>());

    lenient().when(session.getBasicRemote()).thenReturn(basicRemote);
    lenient().doAnswer(invocation -> {
      ByteBuffer buffer = invocation.getArgument(0);
      // Make a copy since the buffer may be reused
      byte[] copy = new byte[buffer.remaining()];
      buffer.get(copy);
      sentMessages.add(ByteBuffer.wrap(copy));
      return null;
    }).when(basicRemote).sendBinary(any(ByteBuffer.class));

    // Mock cipher to just copy input to output with fake 16-byte auth tag
    lenient().when(mockCipher.encryptWithAd(isNull(), any(byte[].class), anyInt(), any(byte[].class), anyInt(), anyInt()))
        .thenAnswer(invocation -> {
          byte[] plaintext = invocation.getArgument(1);
          int plaintextOffset = invocation.getArgument(2);
          byte[] ciphertext = invocation.getArgument(3);
          int ciphertextOffset = invocation.getArgument(4);
          int length = invocation.getArgument(5);
          System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
          return length + 16; // Add fake auth tag length
        });

    websocket = new TestableNoiseConnectionWebsocket(mockCipher);
  }

  @Test
  void sendMessage_concurrentSends_messagesAreNotInterleaved() throws Exception {
    int threadCount = 10;
    int messagesPerThread = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (int t = 0; t < threadCount; t++) {
      int threadId = t;
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < messagesPerThread; i++) {
            String message = "Thread" + threadId + "-Message" + i;
            byte[] data = message.getBytes();
            websocket.testSendMessage(session, data);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          e.printStackTrace();
        } finally {
          doneLatch.countDown();
        }
      });
    }

    // Start all threads at once
    startLatch.countDown();

    // Wait for completion
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
    executor.shutdown();

    assertEquals(0, errorCount.get(), "No errors should occur during concurrent sends");

    // Verify we got the expected number of messages
    // Each message results in at least one frame being sent
    int expectedMessages = threadCount * messagesPerThread;
    assertTrue(sentMessages.size() >= expectedMessages,
        "Should have sent at least " + expectedMessages + " messages, got " + sentMessages.size());
  }

  @Test
  void sendMessage_concurrentMultiframeMessagesRemainContiguous()
      throws Exception
  {
    byte[] firstMessage = new byte[200_000];
    byte[] secondMessage = new byte[200_000];
    Arrays.fill(firstMessage, (byte) 1);
    Arrays.fill(secondMessage, (byte) 2);
    CountDownLatch firstFrameSent = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger sends = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    doAnswer(invocation -> {
      ByteBuffer buffer = invocation.getArgument(0);
      byte[] copy = new byte[buffer.remaining()];
      buffer.get(copy);
      sentMessages.add(ByteBuffer.wrap(copy));
      if (sends.getAndIncrement() == 0) {
        firstFrameSent.countDown();
        assertTrue(releaseFirst.await(1, TimeUnit.SECONDS));
      }
      return null;
    }).when(basicRemote).sendBinary(any(ByteBuffer.class));
    Thread first = Thread.ofPlatform().start(() -> send(
        websocket,
        session,
        firstMessage,
        failure));
    assertTrue(firstFrameSent.await(1, TimeUnit.SECONDS));
    Thread second = Thread.ofPlatform().start(() -> {
      secondStarted.countDown();
      send(websocket, session, secondMessage, failure);
    });
    assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
    awaitWaiting(second);

    try {
      assertEquals(1, sentMessages.size());
    } finally {
      releaseFirst.countDown();
    }
    first.join(TimeUnit.SECONDS.toMillis(1));
    second.join(TimeUnit.SECONDS.toMillis(1));

    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertNull(failure.get());
    NoiseTransportFramer.FrameAssembler assembler =
        new NoiseTransportFramer.FrameAssembler();
    List<byte[]> completeMessages = new ArrayList<>();
    long firstChunkId = 0;
    int firstChunkCount = 0;
    for (int index = 0; index < sentMessages.size(); index++) {
      ByteBuffer encrypted = sentMessages.get(index).duplicate();
      byte[] frame = new byte[encrypted.remaining() - 16];
      encrypted.get(frame);
      confer.NoiseTransport.NoiseTransportFrame decoded =
          NoiseTransportFramer.decodeFrame(frame);
      if (index == 0) {
        firstChunkId = decoded.getChunkId();
        firstChunkCount = decoded.getTotalChunks();
      }
      if (index < firstChunkCount) {
        assertEquals(firstChunkId, decoded.getChunkId());
        assertEquals(index, decoded.getChunkIndex());
      } else {
        assertNotEquals(firstChunkId, decoded.getChunkId());
        assertEquals(index - firstChunkCount, decoded.getChunkIndex());
      }
      byte[] complete = assembler.processFrame(decoded);
      if (complete != null) {
        completeMessages.add(complete);
      }
    }
    assertEquals(2, completeMessages.size());
    assertArrayEquals(firstMessage, completeMessages.get(0));
    assertArrayEquals(secondMessage, completeMessages.get(1));
  }

  @Test
  void sendMessage_singleMessage_sendsCorrectly() throws Exception {
    byte[] testData = "Hello, World!".getBytes();

    websocket.testSendMessage(session, testData);

    // Should have sent at least one message
    assertFalse(sentMessages.isEmpty(), "Should have sent at least one message");
    verify(basicRemote, atLeastOnce()).sendBinary(any(ByteBuffer.class));
  }

  @Test
  void sendMessage_emptyMessage_sendsCorrectly() throws Exception {
    byte[] testData = new byte[0];

    websocket.testSendMessage(session, testData);

    // Empty messages should still be framed and sent
    verify(basicRemote, atLeastOnce()).sendBinary(any(ByteBuffer.class));
  }

  @Test
  void sendMessage_largeMessage_sendsMultipleFrames() throws Exception {
    // Create a message larger than the max chunk size (~65KB)
    byte[] largeData = new byte[100_000];
    for (int i = 0; i < largeData.length; i++) {
      largeData[i] = (byte) (i % 256);
    }

    websocket.testSendMessage(session, largeData);

    // Should have sent multiple frames
    assertTrue(sentMessages.size() > 1, "Large message should be split into multiple frames");
  }

  @Test
  void sendMessage_cipherEncryptsEachFrame() throws Exception {
    byte[] testData = "Test message".getBytes();

    websocket.testSendMessage(session, testData);

    // Verify the cipher was called at least once
    verify(mockCipher, atLeastOnce()).encryptWithAd(isNull(), any(byte[].class), anyInt(), any(byte[].class), anyInt(), anyInt());
  }

  @Test
  void sendMessage_propagatesTransportFailureAndClosesTheSession()
      throws Exception
  {
    when(session.isOpen()).thenReturn(true);
    doThrow(new IOException("connection closed"))
        .when(basicRemote)
        .sendBinary(any(ByteBuffer.class));

    assertThrows(
        IOException.class,
        () -> websocket.testSendMessage(session, "artifact".getBytes()));

    verify(session).close(any(CloseReason.class));
  }

  /**
   * Testable subclass that exposes sendMessage and allows injecting mock cipher
   */
  private static class TestableNoiseConnectionWebsocket extends NoiseConnectionWebsocket {

    TestableNoiseConnectionWebsocket(CipherState cipher) {
      super(null, null);
      // Use reflection to set the sendCipher field
      try {
        java.lang.reflect.Field sendCipherField = NoiseConnectionWebsocket.class.getDeclaredField("sendCipher");
        sendCipherField.setAccessible(true);
        sendCipherField.set(this, cipher);
      } catch (Exception e) {
        throw new RuntimeException("Failed to set mock cipher", e);
      }
    }

    void testSendMessage(Session session, byte[] data) throws IOException {
      sendMessage(session, data);
    }

    @Override
    protected void onReceiveMessage(Session session, byte[] data) {
      // Not used in these tests
    }
  }

  private static void send(TestableNoiseConnectionWebsocket websocket,
                           Session                          session,
                           byte[]                           message,
                           AtomicReference<Throwable>       failure)
  {
    try {
      websocket.testSendMessage(session, message);
    } catch (Throwable error) {
      failure.compareAndSet(null, error);
    }
  }

  private static void awaitWaiting(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.getState() != Thread.State.WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.WAITING, thread.getState());
  }
}
