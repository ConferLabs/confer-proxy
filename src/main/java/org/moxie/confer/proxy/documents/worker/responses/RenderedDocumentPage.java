package org.moxie.confer.proxy.documents.worker.responses;

public record RenderedDocumentPage(RenderedDocumentPageMetadata metadata, byte[] png) {}
