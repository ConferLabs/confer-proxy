package org.moxie.confer.proxy.workers.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.strings.StringMatcher;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import dev.sigstore.trustroot.SigstoreTrustedRoot;
import org.moxie.confer.proxy.workers.WorkerException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;

public class SigstoreWorkerReleaseVerifier {

  private static final boolean USE_STAGING_SIGSTORE = false;

  static final String SIGNER = "worker-releases@conferlabs.iam.gserviceaccount.com";
  private static final String ISSUER = "https://accounts.google.com";

  private static final String PRODUCTION_ROOT = "/META-INF/sigstore-production-trusted-root.json";
  private static final String STAGING_ROOT    = "/META-INF/sigstore-staging-trusted-root.json";

  private static final String ARTIFACT_TYPE = "confer-worker-image";

  private final ObjectMapper   mapper;
  private final BundleVerifier bundles;

  public SigstoreWorkerReleaseVerifier(ObjectMapper mapper) {
    this(mapper, loadSigstore(USE_STAGING_SIGSTORE));
  }

  SigstoreWorkerReleaseVerifier(ObjectMapper mapper,
                                boolean      useStagingSigstore)
  {
    this(mapper, loadSigstore(useStagingSigstore));
  }

  SigstoreWorkerReleaseVerifier(ObjectMapper   mapper,
                                BundleVerifier bundles)
  {
    this.mapper  = mapper;
    this.bundles = bundles;
  }

  void verify(String          manifest,
              String          manifestBundle,
              WorkerTdxClaims claims)
      throws WorkerException
  {
    if (manifest == null || manifest.isEmpty() || manifestBundle == null || manifestBundle.isEmpty()) {
      throw new WorkerException("Worker release evidence is missing");
    }

    byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
    bundles.verify(manifestBytes, manifestBundle);

    ReleaseManifest release;

    try {
      release = mapper.readValue(manifestBytes, ReleaseManifest.class);
    } catch (IOException error) {
      throw new WorkerException("Worker release manifest is invalid", error);
    }

    if (release == null || !release.isValid()) {
      throw new WorkerException("Worker release manifest is invalid");
    }

    if (!release.tdxMeasurements().matches(claims)) {
      throw new WorkerException("Worker release measurements do not match");
    }
  }

  private static BundleVerifier loadSigstore(boolean useStagingSigstore) {
    String resource = useStagingSigstore ? STAGING_ROOT : PRODUCTION_ROOT;

    SigstoreTrustedRoot trustedRoot;

    try (InputStream input = SigstoreWorkerReleaseVerifier.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Sigstore trusted root is missing");
      }

      trustedRoot = SigstoreTrustedRoot.from(input);
    } catch (IOException | SigstoreConfigurationException error) {
      throw new IllegalStateException("Sigstore trusted root is invalid", error);
    }

    KeylessVerifier verifier;

    try {
      verifier = KeylessVerifier.builder()
                                .trustedRootProvider(() -> trustedRoot)
                                .build();
    } catch (InvalidAlgorithmParameterException
             | CertificateException
             | InvalidKeySpecException
             | NoSuchAlgorithmException
             | SigstoreConfigurationException error) {
      throw new IllegalStateException("Sigstore verifier is unavailable", error);
    }

    VerificationOptions options = VerificationOptions.builder()
        .addCertificateMatchers(
            CertificateMatcher.fulcio()
                              .subjectAlternativeName(StringMatcher.string(SIGNER))
                              .issuer(StringMatcher.string(ISSUER))
                              .build())
        .build();

    return (artifact, bundleJson) -> {
      try {
        Bundle bundle = Bundle.from(new StringReader(bundleJson));
        verifier.verify(sha256(artifact), bundle, options);
      } catch (BundleParseException | KeylessVerificationException error) {
        throw new WorkerException("Worker release signature is invalid", error);
      }
    };
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  @FunctionalInterface
  interface BundleVerifier {

    void verify(byte[] artifact, String bundle) throws WorkerException;
  }

  private record ReleaseManifest(String          artifactType,
                                 String          imageVersion,
                                 TdxMeasurements tdxMeasurements,
                                 int             version)
  {
    private boolean isValid() {
      return version == 1
          && ARTIFACT_TYPE.equals(artifactType)
          && tdxMeasurements != null;
    }
  }

  private record TdxMeasurements(String mrtd,
                                 String rtmr0,
                                 String rtmr1,
                                 String rtmr2)
  {
    private boolean matches(WorkerTdxClaims claims) {
      return measurementsEqual(mrtd, claims.mrtd())
          & measurementsEqual(rtmr0, claims.rtmr0())
          & measurementsEqual(rtmr1, claims.rtmr1())
          & measurementsEqual(rtmr2, claims.rtmr2());
    }
  }

  private static boolean measurementsEqual(String first, String second) {
    if (first == null || second == null) {
      return false;
    }

    return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                                 second.getBytes(StandardCharsets.US_ASCII));
  }
}
