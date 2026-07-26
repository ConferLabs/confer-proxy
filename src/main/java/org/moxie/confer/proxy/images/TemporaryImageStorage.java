package org.moxie.confer.proxy.images;

import java.io.IOException;

public interface TemporaryImageStorage {

  ImageReference storePng(byte[] png) throws IOException;
}
