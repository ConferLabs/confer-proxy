package org.moxie.confer.proxy.entities;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {

  private static ValidatorFactory validatorFactory;
  private static Validator        validator;

  @BeforeAll
  static void createValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  void validRequestHasNoViolations() {
    ChatRequest request = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        0.0,
        1.0,
        -1,
        0.0,
        -2.0,
        2.0,
        1.0,
        1,
        true,
        false,
        true,
        true,
        List.of(new ChatRequest.ClientTool("example", "Example tool", Map.of())),
        List.of(new DocumentReference(
            "document-1",
            "example.pdf",
            "application/pdf",
            1,
            "namespace/document-1",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")));

    assertTrue(validator.validate(request).isEmpty());
  }

  @Test
  void invalidTopLevelFieldsAreRejected() {
    ChatRequest request = new ChatRequest(
        List.of(),
        " ",
        -0.1,
        0.0,
        -2,
        1.1,
        -2.1,
        2.1,
        0.0,
        0,
        null,
        null,
        null,
        null,
        null,
        null);

    assertEquals(
        Set.of(
            "messages",
            "model",
            "temperature",
            "topP",
            "topK",
            "minP",
            "presencePenalty",
            "frequencyPenalty",
            "repetitionPenalty",
            "maxTokens",
            "stream"),
        propertyPaths(validator.validate(request)));
  }

  @Test
  void validationCascadesIntoMessagesToolsAndDocuments() {
    ChatRequest request = new ChatRequest(
        List.of(new ChatRequest.Message(null, null, null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        List.of(new ChatRequest.ClientTool("", null, null)),
        List.of(new DocumentReference("!", "", "", 0, "", "")));

    assertEquals(
        Set.of(
            "messages[0].role",
            "messages[0].content",
            "clientTools[0].name",
            "clientTools[0].description",
            "clientTools[0].parameters",
            "documents[0].attachmentId",
            "documents[0].filename",
            "documents[0].contentType",
            "documents[0].sourceBytes",
            "documents[0].sourceObjectKey",
            "documents[0].encryptionKey"),
        propertyPaths(validator.validate(request)));
  }

  private Set<String> propertyPaths(Set<ConstraintViolation<ChatRequest>> violations) {
    return violations.stream()
                     .map(violation -> violation.getPropertyPath().toString())
                     .collect(Collectors.toSet());
  }
}
