package org.moxie.confer.proxy.crypto;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Encrypts plaintext into the Confer chunked authenticated-encryption format.
 */
public class ChunkedCipherOutputStream extends OutputStream {

  private final OutputStream  encrypted;
  private final ChunkedCipher cipher;

  private boolean closed;

  public ChunkedCipherOutputStream(OutputStream encrypted,
                                   SecretKey key)
    throws IOException
  {
    this(encrypted, key, new SecureRandom());
  }

  ChunkedCipherOutputStream(OutputStream encrypted,
                            SecretKey key,
                            SecureRandom random)
    throws IOException
  {
    this.encrypted = Objects.requireNonNull(encrypted, "encrypted");
    this.cipher    = new ChunkedCipher(key, ChunkedCipher.Mode.ENCRYPT, random);

    encrypted.write(cipher.getHeader());
  }

  @Override
  public void write(int value) throws IOException {
    write(new byte[] {(byte) value}, 0, 1);
  }

  @Override
  public void write(byte[] source, int offset, int length) throws IOException {
    requireOpen();
    Objects.checkFromIndexSize(offset, length, source.length);

    try {
      while (length > 0) {
        int updateLength = Math.min(length, cipher.getPlaintextChunkSize());
        encrypted.write(cipher.update(source, offset, updateLength));
        offset += updateLength;
        length -= updateLength;
      }
    } catch (GeneralSecurityException error) {
      throw new IOException("Chunked encryption failed", error);
    }
  }

  @Override
  public void flush() throws IOException {
    requireOpen();
    encrypted.flush();
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    IOException failure = null;
    try {
      encrypted.write(cipher.doFinal());
    } catch (GeneralSecurityException error) {
      failure = new IOException("Chunked encryption failed", error);
    } catch (IOException error) {
      failure = error;
    }

    try {
      encrypted.close();
    } catch (IOException error) {
      if (failure == null) {
        failure = error;
      } else {
        failure.addSuppressed(error);
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Chunked cipher output stream is closed");
    }
  }
}
