package org.moxie.confer.proxy.entities;

import java.util.List;

public record EmbeddingRequest(
  List<String> texts,
  Boolean isQuery
) {}
