package org.moxie.confer.proxy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manifest information loaded from the proxy disk.
 * Contains the Sigstore-signed manifest and bundle for client verification.
 */
public class ManifestInfo {

  private final String manifest;
  private final String manifestBundle;

  public ManifestInfo() {
    this(null, null);
  }

  public ManifestInfo(String manifest, String manifestBundle) {
    this.manifest = manifest;
    this.manifestBundle = manifestBundle;
  }

  /**
   * Load manifest files from the specified paths.
   */
  public static ManifestInfo fromPaths(String manifestPath, String manifestBundlePath) throws IOException {
    String manifest = Files.readString(Path.of(manifestPath));
    String manifestBundle = Files.readString(Path.of(manifestBundlePath));
    return new ManifestInfo(manifest, manifestBundle);
  }

  /**
   * Get the manifest JSON content.
   * Contains image version, proxy version, and TDX measurements.
   */
  public String manifest() {
    return manifest;
  }

  /**
   * Get the Sigstore bundle JSON content.
   * Contains the signature, certificate, and Rekor inclusion proof.
   */
  public String manifestBundle() {
    return manifestBundle;
  }

  /**
   * Check if manifest info is available.
   */
  public boolean isComplete() {
    return manifest != null && manifestBundle != null;
  }
}
