package org.moxie.confer.proxy.producers;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.qualifiers.TogetherAI;
import org.moxie.confer.proxy.qualifiers.VllmAI;

@ApplicationScoped
public class OpenAIClientProducer {

  @Inject
  Config config;

  @Produces
  @TogetherAI
  public OpenAIClient getTogetherAIClient() {
    return OpenAIOkHttpClient.builder()
                             .apiKey(config.getTogetherApiKey())
                             .baseUrl("https://api.together.xyz/v1/")
                             .build();
  }

  @Produces
  @VllmAI
  public OpenAIClient getVllmAiClient() {
    return OpenAIOkHttpClient.builder()
                             .apiKey("dummy")
                             .baseUrl("http://localhost:8000/v1/")
                             .build();
  }

}
