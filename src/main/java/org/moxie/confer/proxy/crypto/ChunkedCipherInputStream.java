package org.moxie.confer.proxy.crypto;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Authenticates and decrypts a stream one chunk at a time.
 */
public class ChunkedCipherInputStream extends InputStream {

  private final PushbackInputStream encrypted;
  private final ChunkedCipher       cipher;
  private final int                 encryptedChunkSize;

  private byte[]  plaintext = new byte[0];
  private int     plaintextOffset;
  private boolean closed;
  private boolean finished;

  public ChunkedCipherInputStream(InputStream encrypted,
                                  String base64Key)
    throws IOException
  {
    this.encrypted = new PushbackInputStream(
        Objects.requireNonNull(encrypted, "encrypted"),
        1);

    try {
      cipher = new ChunkedCipher(base64Key, ChunkedCipher.Mode.DECRYPT);
      byte[] header = readExactly(cipher.getHeaderSize());
      cipher.update(header);
      encryptedChunkSize = cipher.getEncryptedChunkSize();
    } catch (GeneralSecurityException | IllegalArgumentException error) {
      throw new IOException("Encrypted document key or header is invalid", error);
    }
  }

  public long plaintextLength(long encryptedLength) throws IOException {
    try {
      return cipher.getPlaintextLength(encryptedLength);
    } catch (GeneralSecurityException error) {
      throw new IOException("Encrypted document length is invalid", error);
    }
  }

  @Override
  public int read() throws IOException {
    requireOpen();
    byte[] singleByte = new byte[1];
    int read = read(singleByte, 0, 1);
    return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
  }

  @Override
  public int read(byte[] destination,
                  int offset,
                  int length)
    throws IOException
  {
    requireOpen();
    Objects.checkFromIndexSize(offset, length, destination.length);
    if (length == 0) {
      return 0;
    }

    while (plaintextOffset >= plaintext.length) {
      if (finished) {
        return -1;
      }
      decryptNextChunk();
    }

    int copied = Math.min(length, plaintext.length - plaintextOffset);
    System.arraycopy(plaintext, plaintextOffset, destination, offset, copied);
    plaintextOffset += copied;
    return copied;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    encrypted.close();
  }

  private void decryptNextChunk() throws IOException {
    byte[] chunk = encrypted.readNBytes(encryptedChunkSize);
    if (chunk.length == 0) {
      throw new EOFException("Encrypted document contains no chunks");
    }

    boolean finalChunk = isFinalChunk(chunk);

    try {
      plaintext = finalChunk ? cipher.doFinal(chunk) : cipher.update(chunk);
    } catch (GeneralSecurityException error) {
      throw new IOException("Encrypted document authentication failed", error);
    }

    plaintextOffset = 0;
    finished = finalChunk;
  }

  private boolean isFinalChunk(byte[] chunk) throws IOException {
    if (chunk.length < encryptedChunkSize) {
      return true;
    }

    int next = encrypted.read();
    if (next < 0) {
      return true;
    }

    encrypted.unread(next);
    return false;
  }

  private byte[] readExactly(int length)
    throws IOException
  {
    byte[] value = encrypted.readNBytes(length);
    if (value.length != length) {
      throw new EOFException("Encrypted document is truncated");
    }
    return value;
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Chunked cipher input stream is closed");
    }
  }
}
