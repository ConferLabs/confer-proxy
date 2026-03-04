package org.moxie.confer.proxy.services;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EmbeddingService {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

  private static final String MODEL_RESOURCE     = "/models/snowflake-arctic-embed-m-v2.0/model_quantized.onnx";
  private static final String TOKENIZER_RESOURCE = "/models/snowflake-arctic-embed-m-v2.0/tokenizer.json";
  private static final int    DIMENSION          = 768;
  private static final int    MAX_LENGTH         = 512;
  private static final String QUERY_PREFIX       = "query: ";

  private OrtEnvironment       environment;
  private OrtSession           session;
  private HuggingFaceTokenizer tokenizer;

  @PostConstruct
  void initialize() {
    try {
      environment = OrtEnvironment.getEnvironment();

      Path modelPath = extractResource(MODEL_RESOURCE);
      OrtSession.SessionOptions options = new OrtSession.SessionOptions();
      options.setIntraOpNumThreads(2);
      session = environment.createSession(modelPath.toString(), options);

      log.info("ONNX model loaded. Inputs: {}, Outputs: {}",
               session.getInputNames(), session.getOutputNames());

      Path tokenizerPath = extractResource(TOKENIZER_RESOURCE);
      tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
          Map.of("padding",    "true",
                 "truncation", "true",
                 "maxLength",  String.valueOf(MAX_LENGTH)));

      log.info("Tokenizer loaded successfully");
    } catch (OrtException | IOException e) {
      log.error("Failed to initialize embedding service", e);
      throw new RuntimeException("Failed to initialize embedding service", e);
    }
  }

  /**
   * Embed a document or fact (no query prefix).
   */
  public float[] embed(String text) throws OrtException {
    return runInference(text);
  }

  /**
   * Embed a search query (prepends "query: " prefix).
   */
  public float[] embedQuery(String text) throws OrtException {
    return runInference(QUERY_PREFIX + text);
  }

  public List<float[]> embedBatch(List<String> texts) throws OrtException {
    List<float[]> results = new ArrayList<>(texts.size());
    for (String text : texts) {
      results.add(embed(text));
    }
    return results;
  }

  public List<float[]> embedQueryBatch(List<String> texts) throws OrtException {
    List<float[]> results = new ArrayList<>(texts.size());
    for (String text : texts) {
      results.add(embedQuery(text));
    }
    return results;
  }

  public int getDimension() {
    return DIMENSION;
  }

  private float[] runInference(String text) throws OrtException {
    Encoding encoding = tokenizer.encode(text);

    long[] inputIds      = encoding.getIds();
    long[] attentionMask = encoding.getAttentionMask();
    int    seqLength     = inputIds.length;

    OnnxTensor inputIdsTensor      = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), new long[]{1, seqLength});
    OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(attentionMask), new long[]{1, seqLength});

    try (OrtSession.Result result = session.run(Map.of(
        "input_ids",      inputIdsTensor,
        "attention_mask", attentionMaskTensor))) {

      // Model provides pre-pooled sentence_embedding output
      float[][] output = (float[][]) result.get("sentence_embedding").get().getValue();
      return l2Normalize(output[0]);
    } finally {
      inputIdsTensor.close();
      attentionMaskTensor.close();
    }
  }

  private float[] l2Normalize(float[] vec) {
    float sumSq = 0;
    for (float v : vec) {
      sumSq += v * v;
    }

    float   norm   = (float) Math.sqrt(sumSq) + 1e-9f;
    float[] result = new float[vec.length];
    for (int i = 0; i < vec.length; i++) {
      result[i] = vec[i] / norm;
    }

    return result;
  }

  private Path extractResource(String resourcePath) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("Resource not found: " + resourcePath);
      }

      String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
      Path   tempFile = Files.createTempFile("confer-", "-" + fileName);
      tempFile.toFile().deleteOnExit();
      Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
      return tempFile;
    }
  }
}
