package org.moxie.confer.proxy.workers;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyArchiveIT {

  private static final String ARCHIVE_PROPERTY = "proxy.archive";

  @Test
  void shippedArchiveContainsTheCompleteWorkerRuntime() throws Exception {
    Path archivePath = Path.of(requireProperty(ARCHIVE_PROPERTY));
    assertTrue(Files.isRegularFile(archivePath));

    try (ZipFile archive = new ZipFile(archivePath.toFile())) {
      Map<String, ZipEntry> entries = entries(archive);

      require(entries, "proxy.jar");
      require(entries, "libs/jsch-2.28.4.jar");
      require(entries, "libs/sigstore-java-2.2.0.jar");
      require(entries, "libs/protobuf-java-4.33.4.jar");
      require(entries, "document-worker/src/confer_document_worker/main.py");
      require(entries, "document-worker/runtime/docling/backend/msword_backend.py");

      assertFalse(entries.keySet().stream().anyMatch(name -> name.startsWith("native/")));
      assertDocumentWorkerLibrariesAreLinux(archive, entries);

      verifyProxyJar(bytes(archive, entries.get("proxy.jar")));
    }
  }

  private static void assertDocumentWorkerLibrariesAreLinux(
      ZipFile              archive,
      Map<String, ZipEntry> entries)
      throws IOException
  {
    int libraries = 0;
    for (Map.Entry<String, ZipEntry> entry : entries.entrySet()) {
      if (entry.getKey().startsWith("document-worker/runtime/")
          && entry.getKey().endsWith(".so")) {
        assertElf(archive, entry.getValue());
        libraries++;
      }
    }
    assertTrue(libraries > 0, "Proxy archive has no document worker libraries");
  }

  private static Map<String, ZipEntry> entries(ZipFile archive) {
    Map<String, ZipEntry> entries = new HashMap<>();
    archive.stream().forEach(entry -> {
      ZipEntry previous = entries.put(entry.getName(), entry);
      if (previous != null) {
        throw new AssertionError("Duplicate proxy archive entry: " + entry.getName());
      }
    });
    return entries;
  }

  private static void verifyProxyJar(byte[] encodedJar) throws IOException {
    Set<String> entries = new HashSet<>();

    try (JarInputStream jar = new JarInputStream(
        new ByteArrayInputStream(encodedJar))) {
      Manifest manifest = jar.getManifest();
      assertNotNull(manifest);
      assertEquals(
          "ALL-UNNAMED",
          manifest.getMainAttributes().getValue("Enable-Native-Access"));
      assertNotNull(manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));

      JarEntry entry;
      while ((entry = jar.getNextJarEntry()) != null) {
        assertTrue(entries.add(entry.getName()),
                   "Duplicate proxy JAR entry: " + entry.getName());
      }
    }

    assertTrue(entries.contains(
        "org/moxie/confer/proxy/workers/WorkerConnection.class"));
    assertTrue(entries.contains(
        "org/moxie/confer/proxy/workers/attestation/DcapWorkerQuoteVerifier.class"));
    assertTrue(entries.contains(
        "org/moxie/confer/proxy/workers/attestation/SigstoreWorkerReleaseVerifier.class"));
    assertTrue(entries.contains(
        "META-INF/sigstore-production-trusted-root.json"));
    assertTrue(entries.contains(
        "META-INF/sigstore-staging-trusted-root.json"));
  }

  private static void assertElf(ZipFile archive,
                                ZipEntry entry)
      throws IOException
  {
    byte[] header;
    try (InputStream input = archive.getInputStream(entry)) {
      header = input.readNBytes(4);
    }
    assertArrayEquals(new byte[] {0x7f, 'E', 'L', 'F'}, header);
  }

  private static byte[] bytes(ZipFile archive,
                              ZipEntry entry)
      throws IOException
  {
    try (InputStream input = archive.getInputStream(entry);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      input.transferTo(output);
      return output.toByteArray();
    }
  }

  private static void require(Map<String, ZipEntry> entries,
                              String                name)
  {
    ZipEntry entry = entries.get(name);
    assertNotNull(entry, "Missing proxy archive entry: " + name);
    assertFalse(entry.isDirectory(), "Proxy archive entry is a directory: " + name);
    assertTrue(entry.getSize() > 0, "Proxy archive entry is empty: " + name);
  }

  private static String requireProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing system property: " + name);
    }
    return value;
  }
}
