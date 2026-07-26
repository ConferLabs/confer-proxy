package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

public class DocumentToolArguments {

  private final ObjectMapper mapper;
  private final JsonNode values;

  public DocumentToolArguments(ObjectMapper mapper, JsonNode values)
    throws InvalidDocumentToolArgumentsException
  {
    if (values == null || !values.isObject()) {
      throw new InvalidDocumentToolArgumentsException("Tool arguments must be a JSON object");
    }
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.values = values;
  }

  public <T> T decode(Class<T> type)
    throws InvalidDocumentToolArgumentsException
  {
    try {
      return mapper.treeToValue(values, type);
    } catch (JsonProcessingException error) {
      throw new InvalidDocumentToolArgumentsException(
          "Tool arguments are invalid",
          error);
    }
  }

  public String requiredText(String name) throws InvalidDocumentToolArgumentsException {
    String value = optionalText(name);
    if (value == null) {
      throw new InvalidDocumentToolArgumentsException(name + " is required");
    }
    return value;
  }

  public String optionalText(String name) throws InvalidDocumentToolArgumentsException {
    JsonNode value = values.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new InvalidDocumentToolArgumentsException(name + " must be a non-empty string");
    }
    return value.textValue();
  }

  public int boundedInteger(String name,
                            int defaultValue,
                            int minimum,
                            int maximum)
    throws InvalidDocumentToolArgumentsException
  {
    JsonNode value = values.get(name);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.canConvertToInt()) {
      throw new InvalidDocumentToolArgumentsException(name + " must be an integer");
    }
    int parsed = value.intValue();
    if (parsed < minimum || parsed > maximum) {
      throw new InvalidDocumentToolArgumentsException(name + " is outside the allowed range");
    }
    return parsed;
  }

  public int requiredInteger(String name,
                             int minimum,
                             int maximum)
    throws InvalidDocumentToolArgumentsException
  {
    Integer value = optionalInteger(name);
    if (value == null) {
      throw new InvalidDocumentToolArgumentsException(name + " is required");
    }
    if (value < minimum || value > maximum) {
      throw new InvalidDocumentToolArgumentsException(name + " is outside the allowed range");
    }
    return value;
  }

  public Integer optionalInteger(String name) throws InvalidDocumentToolArgumentsException {
    JsonNode value = values.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.canConvertToInt()) {
      throw new InvalidDocumentToolArgumentsException(name + " must be an integer");
    }
    return value.intValue();
  }
}
