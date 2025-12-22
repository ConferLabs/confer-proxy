package org.moxie.confer.proxy.tdx;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;

public class QuoteGenerator {

  private static final File TDX_PATH = new File("/sys/kernel/config/tsm/report/");

  private final byte[] nonce;

  public QuoteGenerator(byte[] nonce) {
    this.nonce = nonce;
  }

  public byte[] generateQuote() throws IOException {
    Path entryDir = Path.of(TDX_PATH.getAbsolutePath(), generateEntryName());
    Path inBlob   = Path.of(entryDir.toAbsolutePath().toString(), "inblob" );
    Path outBlob  = Path.of(entryDir.toAbsolutePath().toString(), "outblob");

    Files.createDirectory(entryDir);

    try {
      byte[] paddedNonce = padNonce(nonce);


      try (OutputStream os = Files.newOutputStream(inBlob, StandardOpenOption.WRITE)) {
        os.write(paddedNonce);
      }

      return Files.readAllBytes(outBlob);
    } finally {
      Files.deleteIfExists(entryDir);
    }
  }

  private String generateEntryName() {
    SecureRandom random = new SecureRandom();
    return "entry" + random.nextInt(0, Integer.MAX_VALUE);
  }

  private static byte[] padNonce(byte[] nonce) {
    if (nonce.length == 64) return nonce;

    byte[] paddedNonce =  new byte[64];
    System.arraycopy(nonce, 0, paddedNonce, 0, nonce.length);

    return paddedNonce;
  }



}
