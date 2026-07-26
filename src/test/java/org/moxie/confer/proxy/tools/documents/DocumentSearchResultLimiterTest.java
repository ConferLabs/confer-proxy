package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.responses.DocumentSearchCollectionResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchDocumentResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchFileResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchMatch;
import org.moxie.confer.proxy.documents.responses.DocumentSearchToolResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSearchResultLimiterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void limitsCollectionResultsWithoutChangingTotalMatchCount() throws Exception {
    DocumentSearchMatch included = hit("a".repeat(28_000));
    DocumentSearchMatch excluded = hit("b".repeat(3_000));
    DocumentSearchCollectionResult result = new DocumentSearchCollectionResult(
        List.of(new DocumentSearchFileResult(
            "doc",
            "Jung.pdf",
            2,
            false,
            null,
            List.of(included, excluded))),
        List.of(),
        2,
        false,
        false,
        null);

    DocumentSearchCollectionResult limited = (DocumentSearchCollectionResult)
        new DocumentSearchResultLimiter(MAPPER).limit(result);

    assertEquals(List.of(included), limited.files().getFirst().results());
    assertEquals(2, limited.matchCount());
    assertEquals(2, limited.files().getFirst().matchCount());
    assertTrue(limited.files().getFirst().hasMoreResults());
    assertTrue(limited.truncated());
  }

  @Test
  void includesResultsFromEveryFileBeforeAddingLowerRankedResults() throws Exception {
    DocumentSearchMatch firstA  = hit("a1" + "a".repeat(7_998));
    DocumentSearchMatch secondA = hit("a2" + "a".repeat(7_998));
    DocumentSearchMatch thirdA  = hit("a3" + "a".repeat(7_998));
    DocumentSearchMatch firstB  = hit("b1" + "b".repeat(7_998));
    DocumentSearchCollectionResult result = new DocumentSearchCollectionResult(
        List.of(
            new DocumentSearchFileResult(
                "a",
                "A.pdf",
                3,
                false,
                null,
                List.of(firstA, secondA, thirdA)),
            new DocumentSearchFileResult(
                "b",
                "B.pdf",
                1,
                false,
                null,
                List.of(firstB))),
        List.of(),
        4,
        false,
        false,
        null);

    DocumentSearchCollectionResult limited = (DocumentSearchCollectionResult)
        new DocumentSearchResultLimiter(MAPPER).limit(result);

    assertEquals(List.of(firstA, secondA), limited.files().get(0).results());
    assertEquals(List.of(firstB), limited.files().get(1).results());
    assertTrue(limited.files().get(0).hasMoreResults());
    assertFalse(limited.files().get(1).hasMoreResults());
    assertTrue(limited.truncated());
  }

  @Test
  void preservesWorkerTruncationWhenEveryCandidateFits() throws Exception {
    DocumentSearchCollectionResult result = new DocumentSearchCollectionResult(
        List.of(new DocumentSearchFileResult(
            "doc",
            "Document.pdf",
            1,
            true,
            null,
            List.of(hit("match")))),
        List.of(),
        1,
        true,
        false,
        null);

    DocumentSearchCollectionResult limited = (DocumentSearchCollectionResult)
        new DocumentSearchResultLimiter(MAPPER).limit(result);

    assertTrue(limited.files().getFirst().hasMoreResults());
    assertTrue(MAPPER.valueToTree(limited)
        .path("files")
        .get(0)
        .path("has_more_results")
        .asBoolean());
    assertTrue(limited.truncated());
  }

  @Test
  void doesNotLimitSingleDocumentResults() throws Exception {
    DocumentSearchToolResult result = new DocumentSearchDocumentResult(
        "doc",
        "Jung.pdf",
        List.of(hit("match")),
        false,
        1,
        null);

    assertSame(result, new DocumentSearchResultLimiter(MAPPER).limit(result));
  }

  private static DocumentSearchMatch hit(String text) {
    return new DocumentSearchMatch(
        1,
        1,
        text,
        List.of(),
        List.of());
  }
}
