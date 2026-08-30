package org.moxie.confer.proxy.attachments;

import java.io.IOException;
import java.io.InputStream;

public interface AttachmentPublisher {

  long MAX_BYTES = 50L * 1024 * 1024;

  AttachmentReference publish(String               attachmentPrefix,
                              String               filename,
                              InputStream          content)
    throws IOException;
}
