package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerConnectionIntegrationTest {

  private static final URI CONTROLLER_URI = URI.create(
      "wss://controller.test/v1/worker");
  private static final String TOKEN = "worker-token";
  private static final String MANIFEST = "{\"version\":1}";
  private static final String MANIFEST_BUNDLE = "{\"bundle\":true}";
  private static final byte[] QUOTE = "authenticated-quote".getBytes(StandardCharsets.US_ASCII);
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15);
  private static final Path WORKSPACE = Path.of("var/lib/confer/workspace");

  @Test
  void allocatesAttestsAuthenticatesAndExecutesOverOneConnection(
      @TempDir Path serverRoot)
  {
    assertTimeout(TEST_TIMEOUT, () -> {
      ObjectMapper mapper = new ObjectMapper();
      KeyPair hostKey = generateEd25519Key();
      AtomicReference<PublicKey> authenticatedClientKey = new AtomicReference<>();
      AtomicReference<String> requestedCommand = new AtomicReference<>();
      SshServer server = startServer(
          hostKey,
          authenticatedClientKey,
          requestedCommand,
          serverRoot);
      AtomicReference<byte[]> verifiedReportData = new AtomicReference<>();
      AtomicBoolean quoteVerified = new AtomicBoolean();
      WorkerQuoteVerifier verifier = (quote, reportData, manifest, bundle) -> {
        assertArrayEquals(QUOTE, quote);
        assertEquals(MANIFEST, manifest);
        assertEquals(MANIFEST_BUNDLE, bundle);
        verifiedReportData.set(reportData);
        quoteVerified.set(true);
      };
      WorkerConnection connection = new WorkerConnection(
          mapper,
          new WorkerAttestationHandshake(mapper, verifier));

      try (Relay relay = new Relay(mapper, server, hostKey.getPublic())) {
        connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN);

        assertTrue(quoteVerified.get());
        assertArrayEquals(relay.getExpectedReportData(), verifiedReportData.get());
        assertNotNull(authenticatedClientKey.get());
        assertEquals("EdDSA", authenticatedClientKey.get().getAlgorithm());

        Map<String, List<String>> headers = new HashMap<>();
        relay.getClientConfig().getConfigurator().beforeRequest(headers);
        assertEquals(List.of("Bearer " + TOKEN), headers.get("Authorization"));

        WorkerCommandResult result = connection.execute("printf 'worker-ready\\n'\n");
        assertEquals(0, result.exitCode());
        assertEquals("worker-ready\n", result.output());
        assertEquals(
            "cd '/var/lib/confer/workspace' && exec /bin/bash --noprofile --norc -s",
            requestedCommand.get());

        WorkerCommandResult second = connection.execute("printf 'worker-reused\\n'\n");
        assertEquals(0, second.exitCode());
        assertEquals("worker-reused\n", second.output());
      } finally {
        connection.close();
        server.stop(true);
      }
    });
  }

  @Test
  void rejectedAttestationNeverStartsSsh(@TempDir Path serverRoot) {
    assertTimeout(TEST_TIMEOUT, () -> {
      ObjectMapper mapper = new ObjectMapper();
      KeyPair hostKey = generateEd25519Key();
      AtomicReference<PublicKey> authenticatedClientKey = new AtomicReference<>();
      SshServer server = startServer(
          hostKey,
          authenticatedClientKey,
          new AtomicReference<>(),
          serverRoot);
      WorkerQuoteVerifier verifier = (quote, reportData, manifest, bundle) -> {
        throw new WorkerException("Untrusted worker");
      };
      WorkerConnection connection = new WorkerConnection(
          mapper,
          new WorkerAttestationHandshake(mapper, verifier));

      try (Relay relay = new Relay(mapper, server, hostKey.getPublic())) {
        assertThrows(
            WorkerException.class,
            () -> connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN));
        assertNull(authenticatedClientKey.get());
      } finally {
        connection.close();
        server.stop(true);
      }
    });
  }

  @Test
  void transfersFilesOverRealSftp(@TempDir Path serverRoot) {
    assertTimeout(TEST_TIMEOUT, () -> {
      ObjectMapper mapper = new ObjectMapper();
      KeyPair hostKey = generateEd25519Key();
      SshServer server = startServer(
          hostKey,
          new AtomicReference<>(),
          new AtomicReference<>(),
          serverRoot);
      WorkerConnection connection = new WorkerConnection(
          mapper,
          new WorkerAttestationHandshake(mapper, (quote, reportData, manifest, bundle) -> {}));
      byte[] content = new byte[192 * 1024];
      for (int index = 0; index < content.length; index++) {
        content[index] = (byte) (index * 31);
      }

      try (Relay relay = new Relay(mapper, server, hostKey.getPublic())) {
        connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN);
        connection.writeFile(
            "nested/input.bin",
            new ByteArrayInputStream(content),
            content.length);

        assertArrayEquals(
            content,
            Files.readAllBytes(serverRoot.resolve(WORKSPACE).resolve("nested/input.bin")));

        try (WorkerConnection.OpenFile file = connection.openFile(
            "nested/input.bin",
            content.length)) {
          assertEquals("input.bin", file.getFilename());
          assertArrayEquals(content, file.getInputStream().readAllBytes());
        }

        try (Stream<Path> files = Files.list(
            serverRoot.resolve(WORKSPACE).resolve("nested"))) {
          assertEquals(
              List.of("input.bin"),
              files.map(path -> path.getFileName().toString()).sorted().toList());
        }
      } finally {
        connection.close();
        server.stop(true);
      }
    });
  }

  @Test
  void sshSurvivesOneByteWebsocketMessages(@TempDir Path serverRoot) {
    assertTimeout(TEST_TIMEOUT, () -> {
      ObjectMapper mapper = new ObjectMapper();
      KeyPair hostKey = generateEd25519Key();
      SshServer server = startServer(
          hostKey,
          new AtomicReference<>(),
          new AtomicReference<>(),
          serverRoot);
      WorkerConnection connection = new WorkerConnection(
          mapper,
          new WorkerAttestationHandshake(mapper, (quote, reportData, manifest, bundle) -> {}));

      try (Relay relay = new Relay(mapper, server, hostKey.getPublic(), true)) {
        connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN);

        WorkerCommandResult first = connection.execute("printf 'fragmented-one\\n'\n");
        WorkerCommandResult second = connection.execute("printf 'fragmented-two\\n'\n");

        assertEquals(0, first.exitCode());
        assertEquals("fragmented-one\n", first.output());
        assertEquals(0, second.exitCode());
        assertEquals("fragmented-two\n", second.output());
      } finally {
        connection.close();
        server.stop(true);
      }
    });
  }

  @Test
  void closeTerminatesACommandWaitingForRemoteOutput(@TempDir Path serverRoot)
      throws Exception
  {
    ObjectMapper mapper = new ObjectMapper();
    KeyPair hostKey = generateEd25519Key();
    CountDownLatch commandOpened = new CountDownLatch(1);
    SshServer server = startServer(
        hostKey,
        new AtomicReference<>(),
        new AtomicReference<>(),
        serverRoot,
        commandOpened);
    WorkerConnection connection = new WorkerConnection(
        mapper,
        new WorkerAttestationHandshake(
            mapper,
            (quote, reportData, manifest, bundle) -> {}));
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (Relay relay = new Relay(mapper, server, hostKey.getPublic())) {
      connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN);
      Thread executing = Thread.ofVirtual().start(() -> {
        try {
          connection.execute("sleep 60");
        } catch (Throwable error) {
          failure.set(error);
        }
      });
      assertTrue(commandOpened.await(2, TimeUnit.SECONDS));

      Thread closing = Thread.ofVirtual().start(connection::close);
      closing.join(TimeUnit.SECONDS.toMillis(2));
      executing.join(TimeUnit.SECONDS.toMillis(2));

      assertFalse(closing.isAlive());
      assertFalse(executing.isAlive());
      assertTrue(failure.get() instanceof WorkerException);
    } finally {
      connection.close();
      server.stop(true);
    }
  }

  @Test
  void closeTerminatesAnSshHandshakeAfterAttestation(@TempDir Path serverRoot)
      throws Exception
  {
    ObjectMapper mapper = new ObjectMapper();
    KeyPair hostKey = generateEd25519Key();
    SshServer server = startServer(
        hostKey,
        new AtomicReference<>(),
        new AtomicReference<>(),
        serverRoot);
    WorkerConnection connection = new WorkerConnection(
        mapper,
        new WorkerAttestationHandshake(
            mapper,
            (quote, reportData, manifest, bundle) -> {}));
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (Relay relay = new Relay(
        mapper,
        server,
        hostKey.getPublic(),
        false,
        true))
    {
      Thread connecting = Thread.ofVirtual().start(() -> {
        try {
          connection.connect(relay.getContainer(), CONTROLLER_URI, TOKEN);
        } catch (Throwable error) {
          failure.set(error);
        }
      });
      assertTrue(relay.awaitSshStart());

      Thread closing = Thread.ofVirtual().start(connection::close);
      closing.join(TimeUnit.SECONDS.toMillis(2));
      connecting.join(TimeUnit.SECONDS.toMillis(2));

      assertFalse(closing.isAlive());
      assertFalse(connecting.isAlive());
      assertTrue(failure.get() instanceof WorkerException);
    } finally {
      connection.close();
      server.stop(true);
    }
  }

  private static SshServer startServer(
      KeyPair                    hostKey,
      AtomicReference<PublicKey> authenticatedClientKey,
      AtomicReference<String>    requestedCommand,
      Path                       serverRoot)
      throws IOException
  {
    return startServer(
        hostKey,
        authenticatedClientKey,
        requestedCommand,
        serverRoot,
        null);
  }

  private static SshServer startServer(
      KeyPair                    hostKey,
      AtomicReference<PublicKey> authenticatedClientKey,
      AtomicReference<String>    requestedCommand,
      Path                       serverRoot,
      CountDownLatch             commandOpened)
      throws IOException
  {
    Files.createDirectories(serverRoot.resolve(WORKSPACE));

    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
    server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
    server.setSubsystemFactories(List.of(
        new SftpSubsystemFactory.Builder().build()));
    server.setPublickeyAuthenticator((username, publicKey, session) -> {
      authenticatedClientKey.set(publicKey);
      return "confer-job".equals(username)
          && "EdDSA".equals(publicKey.getAlgorithm());
    });
    ProcessShellFactory shell = new ProcessShellFactory(
        "/bin/bash --noprofile --norc -s",
        List.of("/bin/bash", "--noprofile", "--norc", "-s"));
    server.setCommandFactory((channel, command) -> {
      requestedCommand.set(command);
      if (commandOpened != null) {
        commandOpened.countDown();
      }
      return shell.createShell(channel);
    });
    server.start();
    return server;
  }

  private static KeyPair generateEd25519Key() throws Exception {
    return SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA)
                        .generateKeyPair();
  }

  private static byte[] getSshKeyBlob(PublicKey publicKey) {
    String encoded = PublicKeyEntry.toString(publicKey);
    String[] fields = encoded.split(" ");
    if (fields.length != 2 || !"ssh-ed25519".equals(fields[0])) {
      throw new IllegalArgumentException("Expected a canonical Ed25519 SSH key");
    }
    return Base64.getDecoder().decode(fields[1]);
  }

  private static class Relay implements AutoCloseable {

    private final ObjectMapper mapper;
    private final SshServer server;
    private final byte[] hostKey;
    private final WebSocketContainer container = mock(WebSocketContainer.class);
    private final Session session = mock(Session.class);
    private final RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
    private final EndpointConfig endpointConfig = mock(EndpointConfig.class);
    private final AtomicReference<MessageHandler.Whole<ByteBuffer>> binaryHandler =
        new AtomicReference<>();

    private ClientEndpointConfig clientConfig;
    private Endpoint endpoint;
    private Socket socket;
    private Thread reader;
    private byte[]               expectedReportData;
    private boolean              attested;
    private final boolean        fragmentSsh;
    private final boolean        delaySshBanner;
    private final CountDownLatch sshStarted = new CountDownLatch(1);

    private Relay(ObjectMapper mapper,
                  SshServer    server,
                  PublicKey    hostKey)
        throws Exception
    {
      this(mapper, server, hostKey, false, false);
    }

    private Relay(ObjectMapper mapper,
                  SshServer    server,
                  PublicKey    hostKey,
                  boolean      fragmentSsh)
        throws Exception
    {
      this(mapper, server, hostKey, fragmentSsh, false);
    }

    private Relay(ObjectMapper mapper,
                  SshServer    server,
                  PublicKey    hostKey,
                  boolean      fragmentSsh,
                  boolean      delaySshBanner)
        throws Exception
    {
      this.mapper         = mapper;
      this.server         = server;
      this.hostKey        = getSshKeyBlob(hostKey);
      this.fragmentSsh    = fragmentSsh;
      this.delaySshBanner = delaySshBanner;
      configureWebSocket();
    }

    private WebSocketContainer getContainer() {
      return container;
    }

    private ClientEndpointConfig getClientConfig() {
      return clientConfig;
    }

    private byte[] getExpectedReportData() {
      return expectedReportData;
    }

    private boolean awaitSshStart() throws InterruptedException {
      return sshStarted.await(2, TimeUnit.SECONDS);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configureWebSocket() throws Exception {
      when(session.isOpen()).thenReturn(true);
      when(session.getBasicRemote()).thenReturn(remote);
      doAnswer(invocation -> {
        binaryHandler.set(invocation.getArgument(1));
        return null;
      }).when(session).addMessageHandler(
          eq(ByteBuffer.class),
          any(MessageHandler.Whole.class));
      when(container.connectToServer(
          any(Endpoint.class),
          any(ClientEndpointConfig.class),
          eq(CONTROLLER_URI)))
          .thenAnswer(invocation -> {
            endpoint     = invocation.getArgument(0);
            clientConfig = invocation.getArgument(1);
            endpoint.onOpen(session, endpointConfig);
            receive("{\"type\":\"ready\"}".getBytes(StandardCharsets.UTF_8));
            return session;
          });
      doAnswer(invocation -> {
        ByteBuffer message = invocation.getArgument(0);
        byte[] data = new byte[message.remaining()];
        message.get(data);
        send(data);
        return null;
      }).when(remote).sendBinary(any(ByteBuffer.class));
      doAnswer(ignored -> {
        closeSocket();
        return null;
      }).when(session).close(any(CloseReason.class));
    }

    private void send(byte[] data) throws Exception {
      if (!attested) {
        attest(data);
        return;
      }

      sshStarted.countDown();
      socket.getOutputStream().write(data);
      socket.getOutputStream().flush();
    }

    private void attest(byte[] requestBytes) throws Exception {
      JsonNode request = mapper.readTree(requestBytes);
      assertEquals(4, request.path("version").intValue());

      byte[] clientKey = decodeOpenSshKey(request.path("clientKey").textValue());
      byte[] challenge = Base64.getUrlDecoder().decode(
          request.path("challenge").textValue());
      expectedReportData = reportData(hostKey, clientKey, challenge);

      Map<String, Object> response = Map.of(
          "version", 4,
          "platform", "TDX",
          "protocol", "SSH-2.0",
          "challenge", request.path("challenge").textValue(),
          "hostKey", openSshKey(hostKey),
          "clientKey", request.path("clientKey").textValue(),
          "quote", Base64.getUrlEncoder().withoutPadding().encodeToString(QUOTE),
          "manifest", MANIFEST,
          "manifestBundle", MANIFEST_BUNDLE);

      connectSshRelay();
      byte[] banner = readSshBanner();

      receive(mapper.writeValueAsBytes(response));
      attested = true;
      if (!delaySshBanner) {
        receiveSsh(banner);
      }
      startSshReader();
    }

    private void connectSshRelay() throws IOException {
      socket = new Socket();
      socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5_000);
    }

    private byte[] readSshBanner() throws IOException {
      ByteArrayOutputStream banner = new ByteArrayOutputStream();
      while (banner.size() < 1024) {
        int value = socket.getInputStream().read();
        if (value == -1) {
          throw new IOException("SSH server closed before sending its banner");
        }
        banner.write(value);
        if (value == '\n') {
          return banner.toByteArray();
        }
      }
      throw new IOException("SSH server banner exceeded its limit");
    }

    private void startSshReader() {
      reader = Thread.ofVirtual().start(() -> {
        byte[] buffer = new byte[32 * 1024];
        try {
          int count;
          while ((count = socket.getInputStream().read(buffer)) != -1) {
            byte[] message = new byte[count];
            System.arraycopy(buffer, 0, message, 0, count);
            receiveSsh(message);
          }
        } catch (IOException error) {
          if (!socket.isClosed()) {
            endpoint.onError(session, error);
          }
        }
      });
    }

    private void receiveSsh(byte[] data) {
      if (!fragmentSsh) {
        receive(data);
        return;
      }

      int offset = 0;
      while (offset < data.length) {
        receive(ByteBuffer.wrap(data, offset, 1));
        offset++;
      }
    }

    private void receive(byte[] data) {
      receive(ByteBuffer.wrap(data));
    }

    private void receive(ByteBuffer data) {
      MessageHandler.Whole<ByteBuffer> handler = binaryHandler.get();
      if (handler == null) {
        throw new IllegalStateException("WebSocket binary handler is missing");
      }
      handler.onMessage(data);
    }

    private static String openSshKey(byte[] key) {
      return "ssh-ed25519 " + Base64.getEncoder().encodeToString(key);
    }

    private static byte[] decodeOpenSshKey(String key) {
      return Base64.getDecoder().decode(key.substring("ssh-ed25519 ".length()));
    }

    private static byte[] reportData(byte[] hostKey,
                                     byte[] clientKey,
                                     byte[] challenge)
        throws Exception
    {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      digest.update("confer.worker.ssh.prelude.v4".getBytes(StandardCharsets.US_ASCII));
      digest.update(hostKey);
      digest.update(clientKey);
      digest.update(challenge);
      return digest.digest();
    }

    @Override
    public void close() throws Exception {
      closeSocket();
      if (reader != null) {
        reader.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(reader.isAlive());
      }
    }

    private void closeSocket() throws IOException {
      if (socket != null) {
        socket.close();
      }
    }
  }
}
