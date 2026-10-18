package org.moxie.confer.proxy.attachments;

import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;

import java.io.IOException;
import java.io.InputStream;

public interface AttachmentPublisher {

  long MAX_BYTES = 50L * 1024 * 1024;

  AttachmentReference publish(String                  attachmentPrefix,
                              String                  filename,
                              InputStream             content,
                              DocumentWorkerScheduler documentWorkers)
    throws IOException;
}
