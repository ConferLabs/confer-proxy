package org.moxie.confer.proxy.documents;

import org.moxie.confer.proxy.crypto.ChunkedCipherOutputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.utils.CancellableOutputStream;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * One encrypted multipart S3 upload.
 *
 * <p>Closing an incomplete upload cancels both its backpressured destination and
 * its asynchronous request. The plaintext input remains caller-owned.</p>
 */
class S3DocumentUpload implements AutoCloseable {

  private static final int BUFFER_BYTES = 64 * 1024;

  private final CompletableFuture<PutObjectResponse> upload;
  private final CancellableOutputStream              destination;

  private boolean started;
  private boolean completed;

  S3DocumentUpload(S3AsyncClient s3, PutObjectRequest request) throws IOException {
    Objects.requireNonNull(s3, "s3");
    Objects.requireNonNull(request, "request");

    BlockingOutputStreamAsyncRequestBody body = AsyncRequestBody.forBlockingOutputStream(null);

    try {
      upload      = s3.putObject(request, body);
      destination = body.outputStream();
    } catch (SdkException error) {
      throw new IOException("Document storage is unavailable", error);
    }
  }

  long write(InputStream content,
             SecretKey   key,
             long        maximumBytes)
    throws IOException
  {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(key, "key");

    if (started) {
      throw new IOException("Document storage upload was already started");
    }

    started = true;

    try {
      ChunkedCipherOutputStream encrypted = new ChunkedCipherOutputStream(destination, key);
      long plaintextBytes = transfer(content, encrypted, maximumBytes);
      encrypted.close();
      await();
      completed = true;
      return plaintextBytes;
    } catch (SdkException error) {
      throw new IOException("Document storage is unavailable", error);
    } catch (CompletionException error) {
      throw new IOException("Document storage is unavailable", error.getCause());
    }
  }

  @Override
  public void close() {
    if (completed) {
      return;
    }
    destination.cancel();
    upload.cancel(false);
  }

  private static long transfer(InputStream              content,
                               ChunkedCipherOutputStream destination,
                               long                     maximumBytes)
    throws IOException
  {
    byte[] buffer = new byte[BUFFER_BYTES];
    long size = 0;
    int length;
    while ((length = content.read(buffer)) != -1) {
      if (length > maximumBytes - size) {
        throw new IOException("Plaintext exceeds storage limit");
      }
      destination.write(buffer, 0, length);
      size += length;
    }
    return size;
  }

  private void await() throws IOException {
    try {
      upload.join();
    } catch (CancellationException error) {
      throw new IOException("Document storage upload was cancelled", error);
    } catch (CompletionException error) {
      throw new IOException("Document storage is unavailable", error.getCause());
    }
  }
}
