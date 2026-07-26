package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.responses.DocumentSearchCollectionResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchFileResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchMatch;
import org.moxie.confer.proxy.documents.responses.DocumentSearchToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DocumentSearchResultLimiter {

  private static final int MAX_RESULT_CHARACTERS = 30_000;

  private final ObjectMapper mapper;

  public DocumentSearchResultLimiter(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public DocumentSearchToolResult limit(DocumentSearchToolResult result)
    throws JsonProcessingException
  {
    if (!(result instanceof DocumentSearchCollectionResult collection)) {
      return result;
    }

    List<DocumentSearchFileResult>   candidates         = collection.files();
    List<List<DocumentSearchMatch>> includedResults    = new ArrayList<>();
    boolean[]                       resultLimitReached = new boolean[candidates.size()];
    int                             maximumFileResults = 0;

    for (DocumentSearchFileResult file : candidates) {
      includedResults.add(new ArrayList<>());
      maximumFileResults = Math.max(maximumFileResults, file.results().size());
    }

    int     characters = 0;
    boolean truncated  = false;

    for (int resultIndex = 0; resultIndex < maximumFileResults; resultIndex++) {
      for (int fileIndex = 0; fileIndex < candidates.size(); fileIndex++) {
        DocumentSearchFileResult file = candidates.get(fileIndex);
        if (resultLimitReached[fileIndex] || resultIndex >= file.results().size()) {
          continue;
        }

        DocumentSearchMatch hit = file.results().get(resultIndex);
        int hitCharacters = mapper.writeValueAsString(hit).length();
        if (characters + hitCharacters > MAX_RESULT_CHARACTERS) {
          resultLimitReached[fileIndex] = true;
          truncated = true;
          continue;
        }

        includedResults.get(fileIndex).add(hit);
        characters += hitCharacters;
      }
    }

    List<DocumentSearchFileResult> files = new ArrayList<>();
    for (int index = 0; index < candidates.size(); index++) {
      files.add(candidates.get(index).withIncludedResults(includedResults.get(index)));
    }

    return collection.withFiles(files, truncated);
  }
}
