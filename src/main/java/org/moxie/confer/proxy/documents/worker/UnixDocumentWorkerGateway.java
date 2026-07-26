package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

@ApplicationScoped
public class UnixDocumentWorkerGateway implements DocumentWorkerGateway {

  private final Path                   socketPath;
  private final ObjectMapper           mapper;
  private final DocumentStorageGateway storage;
  private final Validator              validator;

  @Inject
  public UnixDocumentWorkerGateway(Config config,
                                   ObjectMapper mapper,
                                   DocumentStorageGateway storage,
                                   Validator validator)
  {
    this(Path.of(config.getDocumentWorkerSocketPath()), mapper, storage, validator);
  }

  UnixDocumentWorkerGateway(Path socketPath,
                            ObjectMapper mapper,
                            DocumentStorageGateway storage,
                            Validator validator)
  {
    this.socketPath = socketPath;
    this.mapper     = mapper;
    this.storage    = storage;
    this.validator  = validator;
  }

  @Override
  public DocumentWorkerConnection connect() throws IOException {
    SocketChannel channel     = null;
    boolean       transferred = false;

    try {
      UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
      channel = SocketChannel.open(StandardProtocolFamily.UNIX);
      channel.connect(address);

      DocumentWorkerConnection connection = new DocumentWorkerConnection(
          new DocumentWorkerInputStream(mapper, Channels.newInputStream(channel)),
          new DocumentWorkerOutputStream(mapper, Channels.newOutputStream(channel)),
          storage,
          mapper,
          validator);
      transferred = true;
      return connection;
    } finally {
      if (!transferred && channel != null) {
        channel.close();
      }
    }
  }
}
