package org.moxie.confer.proxy.entities;

import java.util.List;

public record EmbeddingResponse(
  List<float[]> embeddings,
  int           dimension
) {}
