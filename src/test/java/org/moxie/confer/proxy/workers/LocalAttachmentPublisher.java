package org.moxie.confer.proxy.workers;

import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.attachments.AttachmentReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

class LocalAttachmentPublisher implements AttachmentPublisher {

  private static final String EMPTY_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final Path outputDirectory;

  private PublishedFile published;

  LocalAttachmentPublisher(Path outputDirectory) throws IOException {
    this.outputDirectory = Objects.requireNonNull(
        outputDirectory,
        "outputDirectory").toAbsolutePath().normalize();
    Files.createDirectories(this.outputDirectory);
  }

  @Override
  public synchronized AttachmentReference publish(String      attachmentPrefix,
                                                   String      filename,
                                                   InputStream content)
      throws IOException
  {
    Objects.requireNonNull(attachmentPrefix, "attachmentPrefix");
    Objects.requireNonNull(content, "content");
    if (published != null) {
      throw new IOException("The eval already published a file");
    }
    String id = UUID.randomUUID().toString();
    Path destination = outputDirectory.resolve(id + "-" + filename).normalize();
    if (!destination.getParent().equals(outputDirectory)) {
      throw new IOException("Published filename escaped the eval output directory");
    }

    MessageDigest digest = getSha256();
    long size;
    try (OutputStream output = Files.newOutputStream(destination)) {
      size = copy(content, output, digest);
    } catch (IOException error) {
      Files.deleteIfExists(destination);
      throw error;
    }
    String contentType = URLConnection.guessContentTypeFromName(filename);
    AttachmentReference reference = new AttachmentReference(
        id,
        filename,
        contentType == null ? "application/octet-stream" : contentType,
        size,
        EMPTY_KEY,
        "eval/" + id,
        null);
    published = new PublishedFile(
        reference,
        HexFormat.of().formatHex(digest.digest()),
        destination);
    return reference;
  }

  synchronized PublishedFile getPublished(AttachmentReference reference) {
    if (published == null || !published.reference().equals(reference)) {
      throw new IllegalStateException("Published eval file is unavailable");
    }
    return published;
  }

  synchronized PublishedFile getPublished() {
    if (published == null) {
      throw new IllegalStateException("No eval file was published");
    }
    return published;
  }

  private static long copy(InputStream   input,
                           OutputStream  output,
                           MessageDigest digest)
      throws IOException
  {
    byte[] buffer = new byte[8192];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      total += count;
      if (total > AttachmentPublisher.MAX_BYTES) {
        throw new IOException("Published file is too large");
      }
      digest.update(buffer, 0, count);
      output.write(buffer, 0, count);
    }
    return total;
  }

  private static MessageDigest getSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  record PublishedFile(AttachmentReference reference,
                       String              sha256,
                       Path                path) {}
}
