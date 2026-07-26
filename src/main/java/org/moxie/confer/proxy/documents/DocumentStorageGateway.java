package org.moxie.confer.proxy.documents;

import java.io.IOException;

public interface DocumentStorageGateway {

  DecryptedDocument open(String objectKey, String encryptionKey) throws IOException;
}
