package org.moxie.confer.proxy.workers;

import org.moxie.confer.proxy.documents.DecryptedDocument;

import java.io.IOException;

public interface WorkerInputSource {

  DecryptedDocument open(String attachmentId) throws IOException;
}
