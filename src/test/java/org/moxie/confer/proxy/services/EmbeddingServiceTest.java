package org.moxie.confer.proxy.services;

import ai.onnxruntime.OrtException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for EmbeddingService.
 * Loads the actual ONNX model and tokenizer to verify inference works end-to-end.
 */
class EmbeddingServiceTest {

  private static EmbeddingService service;

  @BeforeAll
  static void setUp() {
    service = new EmbeddingService();
    service.initialize();
  }

  @Test
  void embed_returnsCorrectDimension() throws OrtException {
    float[] embedding = service.embed("The user likes burritos");
    assertEquals(768, embedding.length);
  }

  @Test
  void embed_returnsNormalizedVector() throws OrtException {
    float[] embedding = service.embed("Test sentence for normalization");

    float sumSq = 0;
    for (float v : embedding) {
      sumSq += v * v;
    }

    assertEquals(1.0f, sumSq, 0.01f);
  }

  @Test
  void embed_differentTexts_produceDifferentVectors() throws OrtException {
    float[] emb1 = service.embed("The user loves Italian food");
    float[] emb2 = service.embed("Quantum mechanics describes subatomic particles");

    float similarity = cosineSimilarity(emb1, emb2);
    assertTrue(similarity < 0.8f, "Unrelated texts should have low similarity, got: " + similarity);
  }

  @Test
  void embed_similarTexts_produceHighSimilarity() throws OrtException {
    float[] emb1 = service.embed("The user's favorite food is pizza");
    float[] emb2 = service.embed("The user loves eating pizza");

    float similarity = cosineSimilarity(emb1, emb2);
    assertTrue(similarity > 0.7f, "Similar texts should have high similarity, got: " + similarity);
  }

  @Test
  void embedQuery_producesEmbeddingWithQueryPrefix() throws OrtException {
    float[] docEmb   = service.embed("The user's favorite language is Rust");
    float[] queryEmb = service.embedQuery("What programming language does the user prefer?");

    float similarity = cosineSimilarity(docEmb, queryEmb);
    assertTrue(similarity > 0.5f,
        "Query about language preference should match language fact, got: " + similarity);
  }

  @Test
  void embedBatch_returnsOneEmbeddingPerText() throws OrtException {
    List<String> texts = List.of("fact one", "fact two", "fact three");
    List<float[]> embeddings = service.embedBatch(texts);

    assertEquals(3, embeddings.size());
    for (float[] embedding : embeddings) {
      assertEquals(768, embedding.length);
    }
  }

  @Test
  void embedQueryBatch_returnsOneEmbeddingPerText() throws OrtException {
    List<String> queries = List.of("query one", "query two");
    List<float[]> embeddings = service.embedQueryBatch(queries);

    assertEquals(2, embeddings.size());
    for (float[] embedding : embeddings) {
      assertEquals(768, embedding.length);
    }
  }

  @Test
  void embed_deterministicForSameInput() throws OrtException {
    float[] emb1 = service.embed("Determinism test");
    float[] emb2 = service.embed("Determinism test");

    assertArrayEquals(emb1, emb2);
  }

  @Test
  void getDimension_returns768() {
    assertEquals(768, service.getDimension());
  }

  @Test
  void embed_emptyString_doesNotThrow() throws OrtException {
    float[] embedding = service.embed("");
    assertEquals(768, embedding.length);
  }

  @Test
  void embed_longText_truncatesWithoutError() throws OrtException {
    String longText = "word ".repeat(1000);
    float[] embedding = service.embed(longText);
    assertEquals(768, embedding.length);
  }

  private float cosineSimilarity(float[] a, float[] b) {
    float dot = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
    }
    // Vectors are already L2-normalized, so dot product = cosine similarity
    return dot;
  }
}
