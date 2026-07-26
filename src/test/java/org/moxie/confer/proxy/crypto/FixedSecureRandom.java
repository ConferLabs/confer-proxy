package org.moxie.confer.proxy.crypto;

import java.security.SecureRandom;

class FixedSecureRandom extends SecureRandom {

  private final byte[] entropy;
  private int offset;

  FixedSecureRandom(byte[] entropy) {
    this.entropy = entropy;
  }

  @Override
  public void nextBytes(byte[] bytes) {
    System.arraycopy(entropy, offset, bytes, 0, bytes.length);
    offset += bytes.length;
  }
}
