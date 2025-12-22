package org.moxie.confer.proxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;
import confer.NoiseTransport.NoiseTransportFrame;
import jakarta.websocket.*;
import org.moxie.confer.proxy.attestation.AttestationException;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.producers.NoiseKeyProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public abstract class NoiseConnectionWebsocket {

  private static final Logger log = LoggerFactory.getLogger(NoiseConnectionWebsocket.class);
  
  private static final int MAX_NOISE_MESSAGE_SIZE     = 65535;
  private static final int MAX_HANDSHAKE_MESSAGE_SIZE =  4096;

  private final AttestationService                  attestationService;
  private final ObjectMapper                        mapper;
  private final NoiseTransportFramer.FrameAssembler frameAssembler;

  private HandshakeState handshakeState;
  private CipherState    sendCipher;
  private CipherState    receiveCipher;
  private boolean        serverPayloadSent;

  private enum Phase {HANDSHAKE, ESTABLISHED, FAILED};
  private Phase phase =  Phase.HANDSHAKE;

  protected NoiseConnectionWebsocket(AttestationService attestationService, ObjectMapper mapper)
  {
    this.attestationService = attestationService;
    this.mapper             = mapper;
    this.frameAssembler     = new NoiseTransportFramer.FrameAssembler();
  }

  @OnOpen
  public void onOpen(Session session) {
    log.info("WebSocket connection opened: {}", session.getId());

    try {
      this.handshakeState = new HandshakeState(NoiseKeyProducer.NOISE_PROTOCOL, HandshakeState.RESPONDER);
      this.handshakeState.getLocalKeyPair().setPrivateKey(attestationService.getRawPrivateKey(), 0);
      this.handshakeState.start();
      this.serverPayloadSent = false;

      byte[] ourPublic = new byte[32];
      this.handshakeState.getLocalKeyPair().getPublicKey(ourPublic, 0);

    } catch (NoSuchAlgorithmException e) {
      log.error("Failed to initialize Noise handshake", e);
      phase = Phase.FAILED;
      closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Noise init failed");
    }
  }

  @OnClose
  public void onClose(Session session, CloseReason closeReason) {
    log.info("WebSocket connection closed: {} - Code: {}, Reason: {}",
             session.getId(), closeReason.getCloseCode(), closeReason.getReasonPhrase());
  }

  @OnError
  public void onError(Session session, Throwable throwable) {
    log.error("WebSocket error for session: {}", session.getId(), throwable);
  }

  @OnMessage
  public void onBinary(Session session, ByteBuffer message) {
    if (message.remaining() > MAX_NOISE_MESSAGE_SIZE) {
      log.warn("Message too large: {}", message.remaining());
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Message too large");
      return;
    }

    switch (phase) {
      case FAILED:
        closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Failed");
        return;
      case HANDSHAKE:
        handleReceiveHandshakeMessage(session, message);
        return;
      case ESTABLISHED:
        handleReceiveEncryptedMessage(session, message);
        return;
    }
  }

  private void handleReceiveHandshakeMessage(Session session, ByteBuffer message) {
    if (message.remaining() > MAX_HANDSHAKE_MESSAGE_SIZE) {
      phase = Phase.FAILED;
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Handshake message too large");
      return;
    }
    
    try {
      byte[] in = new byte[message.remaining()];
      message.get(in);
      handshakeState.readMessage(in, 0, in.length, new byte[0], 0);

      while (true) {
        int action = handshakeState.getAction();

        if (action == HandshakeState.WRITE_MESSAGE) {
          byte[] packet = new byte[65535];
          // Serialize attestation response as JSON: {"platform": "TDX", "attestation": "..."}
          byte[] payload = (!serverPayloadSent) ? mapper.writeValueAsString(attestationService.getSignedAttestation()).getBytes() : new byte[0];
          int wlen = (payload != null)
                     ? handshakeState.writeMessage(packet, 0, payload, 0, payload.length)
                     : handshakeState.writeMessage(packet, 0, null, 0, 0);           // no payload

          serverPayloadSent = true;
          session.getBasicRemote().sendBinary(ByteBuffer.wrap(packet, 0, wlen));
        } else if (action == HandshakeState.SPLIT) {
          CipherStatePair pair = handshakeState.split();

          this.sendCipher    = pair.getSender();
          this.receiveCipher = pair.getReceiver();
          this.handshakeState.destroy();
          this.handshakeState = null;
          this.phase          = Phase.ESTABLISHED;
          log.info("We are established");
          break;
        } else if (action == HandshakeState.READ_MESSAGE) {
          break;
        } else {
          throw new IllegalStateException("Handshake failed");
        }
      }
    } catch (ShortBufferException | BadPaddingException | AttestationException | IOException e) {
      log.warn("Handshake failed", e);
      closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Handshake failed");
      this.phase = Phase.FAILED;
    }
  }

  private void handleReceiveEncryptedMessage(Session session, ByteBuffer message) {
    if (phase != Phase.ESTABLISHED || receiveCipher == null) {
      log.warn("Receive encrypted message in unexpected state");
      this.phase = Phase.FAILED;
      closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Received message in unexpected state");
      return;
    }

    try {
      byte[] ciphertext = new byte[message.remaining()];
      message.get(ciphertext);

      // 1. Transport layer: decrypt
      byte[] frameBytes = new byte[ciphertext.length];
      int frameBytesLength = this.receiveCipher.decryptWithAd(null, ciphertext, 0, frameBytes, 0, ciphertext.length);

      // 2. Framing layer: decode and assemble
      byte[] frameBytesExact = new byte[frameBytesLength];
      System.arraycopy(frameBytes, 0, frameBytesExact, 0, frameBytesLength);

      NoiseTransportFrame frame = NoiseTransportFramer.decodeFrame(frameBytesExact);
      byte[] completeMessage = frameAssembler.processFrame(frame);

      // 3. Application layer: handle complete message (only when all frames received)
      if (completeMessage != null) {
        onReceiveMessage(session, completeMessage, 0, completeMessage.length);
      }
    } catch (ShortBufferException | BadPaddingException e) {
      log.warn("Decryption failed", e);
      this.phase = Phase.FAILED;
      closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Decryption failed");
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      log.warn("Failed to decode frame", e);
      this.phase = Phase.FAILED;
      closeQuiet(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Frame decode failed");
    }
  }

  protected abstract void onReceiveMessage(Session session, byte[] data, int offset, int length);

  protected void sendMessage(Session session, byte[] data, int offset, int length) {
    try {
      // 1. Application layer: extract the message bytes
      byte[] messageBytes = new byte[length];
      System.arraycopy(data, offset, messageBytes, 0, length);

      // 2. Framing layer: split into frames
      List<byte[]> frames = NoiseTransportFramer.encodeFrames(messageBytes);

      // 3. Transport layer: encrypt and send each frame
      for (byte[] frame : frames) {
        byte[] ciphertext = new byte[frame.length + 16];
        int ciphertextLength = sendCipher.encryptWithAd(null, frame, 0, ciphertext, 0, frame.length);

        session.getBasicRemote().sendBinary(ByteBuffer.wrap(ciphertext, 0, ciphertextLength));
      }
    } catch (ShortBufferException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      log.warn("Failed to send encrypted message", e);
    }
  }

  protected static void closeQuiet(Session s, CloseReason.CloseCode code, String msg) {
    try {
      if (s.isOpen()) s.close(new CloseReason(code, msg));
    } catch (Exception ignore) {
      log.warn("Failed to close session", ignore);
    }
  }

}
