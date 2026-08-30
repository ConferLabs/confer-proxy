package org.moxie.confer.proxy.workers;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerSshSessionFactoryTest {

  @Test
  void createsAnEphemeralCanonicalEd25519Identity() throws Exception {
    WorkerSshSessionFactory factory = WorkerSshSessionFactory.create();
    byte[] blob = factory.getPublicKey();
    ByteBuffer key = ByteBuffer.wrap(blob);
    byte[] algorithm = new byte[key.getInt()];
    key.get(algorithm);

    assertEquals(51, blob.length);
    assertArrayEquals("ssh-ed25519".getBytes(StandardCharsets.US_ASCII),
                      algorithm);
    assertEquals(32, key.getInt());
    assertEquals(32, key.remaining());
  }
}
