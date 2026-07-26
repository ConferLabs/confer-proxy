package org.moxie.confer.proxy.producers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.moxie.confer.proxy.config.Config;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

@ApplicationScoped
public class S3ClientProducer {

  @Inject
  Config config;

  @Produces
  @Singleton
  public S3Client produceS3Client() {
    return S3Client.builder()
                   .region(Region.of(config.getS3Region()))
                   .build();
  }

  @Produces
  @Singleton
  public S3AsyncClient produceS3AsyncClient() {
    return S3AsyncClient.builder()
                        .region(Region.of(config.getS3Region()))
                        .multipartEnabled(true)
                        .build();
  }
}
