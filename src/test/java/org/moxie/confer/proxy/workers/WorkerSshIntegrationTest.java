package org.moxie.confer.proxy.workers;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerSshIntegrationTest {

  private static final byte[] PRELUDE =
      "consumed-attestation".getBytes(StandardCharsets.US_ASCII);
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

  @Test
  void authenticatesOverTheAttestedStream() {
    assertTimeout(TEST_TIMEOUT, () -> {
      WorkerSshSessionFactory ssh = WorkerSshSessionFactory.create();
      KeyPair hostKey = generateEd25519Key();
      SshServer server = startServer(hostKey, ssh.getPublicKey());
      try {
        try (Socket socket = connect(server);
             WorkerWebsocketConnection webSocket =
                 getWebSocket(socket, PRELUDE)) {
          assertArrayEquals(PRELUDE,
                            webSocket.getInputStream().readNBytes(PRELUDE.length));

          Session session = ssh.connect(
              webSocket,
              getSshKeyBlob(hostKey.getPublic()));
          try {
            assertTrue(session.isConnected());
          } finally {
            session.disconnect();
          }
        }
      } finally {
        server.stop(true);
      }
    });
  }

  @Test
  void rejectsAHostKeyThatDoesNotMatchTheAttestation() {
    assertTimeout(TEST_TIMEOUT, () -> {
      WorkerSshSessionFactory ssh = WorkerSshSessionFactory.create();
      KeyPair hostKey = generateEd25519Key();
      SshServer server = startServer(hostKey, ssh.getPublicKey());
      try {
        try (Socket socket = connect(server);
             WorkerWebsocketConnection webSocket =
                 getWebSocket(socket, PRELUDE)) {
          assertArrayEquals(PRELUDE,
                            webSocket.getInputStream().readNBytes(PRELUDE.length));

          byte[] differentHostKey =
              getSshKeyBlob(generateEd25519Key().getPublic());
          assertThrows(JSchException.class,
                       () -> ssh.connect(webSocket, differentHostKey));
        }
      } finally {
        server.stop(true);
      }
    });
  }

  private static SshServer startServer(KeyPair hostKey,
                                       byte[]  acceptedClientKey)
      throws IOException
  {
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
    server.setPublickeyAuthenticator(
        (username, publicKey, session) ->
            "confer-job".equals(username)
            && MessageDigest.isEqual(acceptedClientKey,
                                     getSshKeyBlob(publicKey)));
    ProcessShellFactory shell = new ProcessShellFactory(
        "/bin/bash --noprofile --norc -s",
        List.of("/bin/bash", "--noprofile", "--norc", "-s"));
    server.setCommandFactory((channel, ignoredCommand) -> shell.createShell(channel));
    server.start();
    return server;
  }

  private static Socket connect(SshServer server) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5_000);
    return socket;
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

  private static WorkerWebsocketConnection getWebSocket(Socket socket,
                                                         byte[] prelude)
      throws IOException
  {
    WorkerWebsocketConnection webSocket =
        mock(WorkerWebsocketConnection.class);
    InputStream input = new SequenceInputStream(
        new ByteArrayInputStream(prelude),
        socket.getInputStream());
    when(webSocket.getInputStream()).thenReturn(input);
    when(webSocket.getOutputStream()).thenReturn(socket.getOutputStream());
    doAnswer(invocation -> {
      socket.setSoTimeout(invocation.getArgument(0));
      return null;
    }).when(webSocket).setReadTimeout(anyInt());
    doAnswer(ignored -> {
      socket.close();
      return null;
    }).when(webSocket).close();
    return webSocket;
  }
}
