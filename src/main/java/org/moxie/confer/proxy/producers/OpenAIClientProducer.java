package org.moxie.confer.proxy.producers;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.moxie.confer.proxy.qualifiers.VllmAI;

@ApplicationScoped
public class OpenAIClientProducer {

  @Produces
  @VllmAI
  public OpenAIClient getVllmAiClient() {
    return OpenAIOkHttpClient.builder()
                             .apiKey("dummy")
                             .baseUrl("http://localhost:8000/v1/")
                             .build();
  }

}
