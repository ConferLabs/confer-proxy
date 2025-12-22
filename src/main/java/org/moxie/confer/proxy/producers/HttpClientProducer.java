package org.moxie.confer.proxy.producers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.http.HttpClient;

@ApplicationScoped
public class HttpClientProducer {

  @Produces
  @ApplicationScoped
  public HttpClient getClient() {
    return HttpClient.newHttpClient();
  }
}
