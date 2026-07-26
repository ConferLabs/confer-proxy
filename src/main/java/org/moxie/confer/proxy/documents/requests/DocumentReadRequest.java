package org.moxie.confer.proxy.documents.requests;

public record DocumentReadRequest(String attachmentId,
                                  int pageNumber,
                                  int pageCount) {}
