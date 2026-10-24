package org.moxie.confer.proxy.producers;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.config.Config;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ClientProducerTest {

  // Building the clients loads their HTTP implementations, which fails at runtime when
  // Maven resolves an older HttpComponents release than the SDK needs.
  @Test
  void buildsTheSyncAndAsyncClients() {
    Config config = mock(Config.class);
    when(config.getS3Region()).thenReturn("us-east-1");

    S3ClientProducer producer = new S3ClientProducer();
    producer.config = config;

    try (S3Client s3 = producer.produceS3Client();
         S3AsyncClient asyncS3 = producer.produceS3AsyncClient())
    {
      assertNotNull(s3);
      assertNotNull(asyncS3);
    }
  }
}
