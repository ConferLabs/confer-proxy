package org.moxie.confer.proxy.documents;

import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DocumentManifest {

  private final Map<String, DocumentReference> references;

  public DocumentManifest(List<DocumentReference> references)
    throws InvalidDocumentManifestException
  {
    this.references = validate(references);
  }

  public List<DocumentReference> values() {
    return List.copyOf(references.values());
  }

  public DocumentReference reference(String attachmentId) throws DocumentAccessException {
    DocumentReference reference = references.get(attachmentId);
    if (reference == null) {
      throw new DocumentAccessException("Unknown attachment_id");
    }
    return reference;
  }

  private Map<String, DocumentReference> validate(List<DocumentReference> values)
    throws InvalidDocumentManifestException
  {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }

    Map<String, DocumentReference> result = new LinkedHashMap<>();

    for (DocumentReference value : values) {
      validateCapabilities(value);

      if (result.putIfAbsent(value.attachmentId(), value) != null) {
        throw new InvalidDocumentManifestException("Duplicate document reference");
      }
    }

    return Collections.unmodifiableMap(result);
  }

  private void validateCapabilities(DocumentReference value)
    throws InvalidDocumentManifestException
  {
    if (!value.sourceObjectKey().endsWith("/" + value.attachmentId())) {
      throw new InvalidDocumentManifestException("Object storage key does not match attachment");
    }

    try {
      new DocumentObjectKeys(value.sourceObjectKey());
    } catch (InvalidObjectStorageKeyException error) {
      throw new InvalidDocumentManifestException("Object storage key is invalid", error);
    }

    try {
      if (Base64.getDecoder().decode(value.encryptionKey()).length != 32) {
        throw new InvalidDocumentManifestException("Document encryption key is invalid");
      }
    } catch (IllegalArgumentException error) {
      throw new InvalidDocumentManifestException("Document encryption key is invalid", error);
    }
  }
}
