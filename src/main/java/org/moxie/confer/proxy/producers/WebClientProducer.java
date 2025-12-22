package org.moxie.confer.proxy.producers;

import io.helidon.webclient.http1.Http1Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class WebClientProducer {

    @Produces
    @Singleton
    public Http1Client produceHttp1Client() {
        return Http1Client.builder()
            .keepAlive(true)
            .maxInMemoryEntity(1024 * 1024) // 1MB
            .build();
    }
}