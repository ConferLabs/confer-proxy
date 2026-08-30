package org.moxie.confer.proxy.attachments;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AttachmentReference(String id,
                                  String filename,
                                  String contentType,
                                  long   size,
                                  String encryptionKey,
                                  String sourceObjectKey,
                                  @JsonInclude(JsonInclude.Include.NON_NULL)
                                  Long extractedTextLength) {}
