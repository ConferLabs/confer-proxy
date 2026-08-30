package org.moxie.confer.proxy.workers;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

class WorkerSshSessionFactory {

  private static final int SSH_CONNECT_TIMEOUT_MILLIS    = 10_000;
  private static final int SSH_KEEPALIVE_INTERVAL_MILLIS = 15_000;
  private static final int SSH_KEEPALIVE_FAILURES        = 1;

  private static final String HOST = "attested-worker";

  private final JSch   jsch;
  private final byte[] publicKey;

  private WorkerSshSessionFactory(JSch jsch,
                                  byte[] publicKey)
  {
    this.jsch = jsch;
    this.publicKey = publicKey;
  }

  static WorkerSshSessionFactory create() throws JSchException {
    JSch jsch = new JSch();
    KeyPair keyPair = KeyPair.genKeyPair(jsch, KeyPair.ED25519);
    ByteArrayOutputStream privateKey = new ByteArrayOutputStream();
    keyPair.writeOpenSSHv1PrivateKey(privateKey, null);
    jsch.addIdentity("ephemeral-worker-client", privateKey.toByteArray(), null, null);

    return new WorkerSshSessionFactory(jsch, keyPair.getPublicKeyBlob());
  }

  byte[] getPublicKey() {
    return publicKey;
  }

  Session connect(WorkerWebsocketConnection webSocket,
                  byte[]                    hostKey)
      throws JSchException
  {
    setKnownHost(hostKey);
    Session session = jsch.getSession("confer-job", HOST, 22);
    session.setSocketFactory(new StreamSocketFactory(webSocket));
    session.setConfig("StrictHostKeyChecking", "yes");
    session.setConfig("PreferredAuthentications", "publickey");
    session.setConfig("PubkeyAcceptedAlgorithms", "ssh-ed25519");
    session.setConfig("server_host_key", "ssh-ed25519");

    try {
      session.setServerAliveInterval(SSH_KEEPALIVE_INTERVAL_MILLIS);
      session.setServerAliveCountMax(SSH_KEEPALIVE_FAILURES);
      session.connect(SSH_CONNECT_TIMEOUT_MILLIS);
      return session;
    } catch (JSchException error) {
      session.disconnect();
      throw error;
    }
  }

  private void setKnownHost(byte[] hostKey) throws JSchException {
    String knownHost = HOST
        + " ssh-ed25519 "
        + Base64.getEncoder().encodeToString(hostKey)
        + "\n";

    jsch.setKnownHosts(new ByteArrayInputStream(knownHost.getBytes(StandardCharsets.US_ASCII)));
  }

  private static class StreamSocketFactory implements SocketFactory {

    private final WorkerWebsocketConnection webSocket;

    private StreamSocketFactory(WorkerWebsocketConnection webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public Socket createSocket(String ignoredHost, int ignoredPort) {
      return new StreamSocket(webSocket);
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
      return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
      return socket.getOutputStream();
    }
  }

  private static class StreamSocket extends Socket {

    private final WorkerWebsocketConnection webSocket;

    private StreamSocket(WorkerWebsocketConnection webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public InputStream getInputStream() {
      return webSocket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return webSocket.getOutputStream();
    }

    @Override
    public void setTcpNoDelay(boolean ignoredEnabled) {}

    @Override
    public void setSoTimeout(int timeoutMillis) {
      webSocket.setReadTimeout(timeoutMillis);
    }

    @Override
    public void close() {
      webSocket.close();
    }

    @Override
    public boolean isConnected() {
      return true;
    }
  }
}
