package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.auth.JWT;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.lifecycle.ManagedResource;
import org.moxie.confer.proxy.workers.attestation.DcapWorkerQuoteVerifier;
import org.moxie.confer.proxy.workers.attestation.SigstoreWorkerReleaseVerifier;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class WorkerClient {

  private final WebSocketContainer         webSockets;
  private final URI                        controllerUri;
  private final ObjectMapper               mapper;
  private final JWT                        jwt;
  private final WorkerAttestationHandshake attestation;
  private final AttachmentPublisher        attachments;

  @Inject
  public WorkerClient(ObjectMapper        mapper,
                      Config              config,
                      AttachmentPublisher attachments)
  {
    this(ContainerProvider.getWebSocketContainer(),
         getControllerUri(config.getWorkerControllerUri()),
         mapper,
         new JWT(config.getJwtSecret()),
         new WorkerAttestationHandshake(mapper, getQuoteVerifier(mapper)),
         attachments);
  }

  WorkerClient(WebSocketContainer         webSockets,
               URI                        controllerUri,
               ObjectMapper               mapper,
               JWT                        jwt,
               WorkerAttestationHandshake attestation,
               AttachmentPublisher        attachments)
  {
    this.webSockets    = webSockets;
    this.controllerUri = controllerUri;
    this.mapper        = mapper;
    this.jwt           = jwt;
    this.attestation   = attestation;
    this.attachments   = attachments;
  }

  public WorkerWorkspace getWorkspace(String attachmentPrefix) {
    return new WorkerWorkspace(new Session(attachmentPrefix), attachments, attachmentPrefix);
  }

  private static WorkerQuoteVerifier getQuoteVerifier(ObjectMapper mapper)
  {
    return new DcapWorkerQuoteVerifier(
        new SigstoreWorkerReleaseVerifier(mapper));
  }

  private static URI getControllerUri(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Worker controller WebSocket URI is missing");
    }

    try {
      URI uri = URI.create(value);

      if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
        throw new IllegalStateException("Worker controller WebSocket URI is invalid");
      }

      return uri;
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("Worker controller WebSocket URI is invalid", error);
    }
  }

  class Session implements ManagedResource {

    private final String subject;
    private final AtomicReference<WorkerConnection> connection = new AtomicReference<>();

    private volatile boolean closed;

    Session(String subject) {
      this.subject = subject;
    }

    synchronized WorkerConnection getConnection() throws WorkerException {
      if (closed) {
        throw new WorkerException("Worker connection attempt was cancelled");
      }

      WorkerConnection existing = connection.get();

      if (existing != null) {
        if (existing.isOpen()) {
          return existing;
        }
        releaseConnection();
      }

      WorkerConnection candidate = new WorkerConnection(mapper, attestation);
      connection.set(candidate);

      if (closed) {
        releaseConnection();
        throw new WorkerException("Worker connection attempt was cancelled");
      }

      try {
        candidate.connect(webSockets, controllerUri, jwt.generate(subject));
        if (closed) {
          throw new WorkerException("Worker connection attempt was cancelled");
        }
        return candidate;
      } catch (WorkerException error) {
        releaseConnection();
        if (closed) {
          throw new WorkerException("Worker connection attempt was cancelled");
        }
        throw error;
      } catch (RuntimeException error) {
        releaseConnection();
        throw error;
      }
    }

    @Override
    public void close() {
      closed = true;
      releaseConnection();
    }

    private void releaseConnection() {
      WorkerConnection closing = connection.getAndSet(null);
      if (closing != null) {
        closing.close();
      }
    }
  }
}
