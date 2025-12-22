package org.moxie.confer.proxy.producers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.moxie.confer.proxy.controllers.OpenAIWebsocketHandler;
import org.moxie.confer.proxy.qualifiers.TogetherAI;
import org.moxie.confer.proxy.qualifiers.VllmAI;
import org.moxie.confer.proxy.tools.ToolRegistry;

@ApplicationScoped
public class OpenAIWebsocketHandlerProducer {

  @Inject
  @TogetherAI
  OpenAIClient togetherAIClient;

  @Inject
  @VllmAI
  OpenAIClient vllmAIClient;

  @Inject
  ObjectMapper mapper;

  @Inject
  ToolRegistry toolRegistry;

  @Produces
  @Named("together")
  @ApplicationScoped
  public OpenAIWebsocketHandler produceTogetherAIHandler() {
    return new OpenAIWebsocketHandler(togetherAIClient, mapper, toolRegistry);
  }

  @Produces
  @Named("vllm")
  @ApplicationScoped
  public OpenAIWebsocketHandler produceVllmAiHandler() {
    return new OpenAIWebsocketHandler(vllmAIClient, mapper, toolRegistry);
  }

}
