package org.moxie.confer.proxy.producers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.controllers.OpenAIWebsocketHandler;
import org.moxie.confer.proxy.documents.DocumentToolSessionFactory;
import org.moxie.confer.proxy.presenters.OpenAIMessagePresenter;
import org.moxie.confer.proxy.qualifiers.VllmAI;
import org.moxie.confer.proxy.tools.registry.ToolRegistry;
import org.moxie.confer.proxy.workers.WorkerClient;

@ApplicationScoped
public class OpenAIWebsocketHandlerProducer {

  @Inject
  @VllmAI
  OpenAIClient vllmAIClient;

  @Inject
  ObjectMapper mapper;

  @Inject
  ToolRegistry toolRegistry;

  @Inject
  Config config;

  @Inject
  OpenAIMessagePresenter messagePresenter;

  @Inject
  DocumentToolSessionFactory documentToolSessions;

  @Inject
  Validator validator;

  @Inject
  WorkerClient workerClient;

  @Produces
  @Named("vllm")
  @ApplicationScoped
  public OpenAIWebsocketHandler produceVllmAiHandler() {
    return new OpenAIWebsocketHandler(
        vllmAIClient,
        mapper,
        toolRegistry,
        config,
        messagePresenter,
        documentToolSessions,
        validator,
        workerClient);
  }

}
