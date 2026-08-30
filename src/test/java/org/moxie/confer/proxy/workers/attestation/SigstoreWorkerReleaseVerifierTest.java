package org.moxie.confer.proxy.workers.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.producers.ObjectMapperProducer;
import org.moxie.confer.proxy.workers.WorkerException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigstoreWorkerReleaseVerifierTest {

  private static final String BUNDLE = "{\"signed\":true}";
  private static final String RELEASE_FIXTURE =
      "/workers/staging-signed-release/";
  private static final String PROXY_RELEASE_FIXTURE =
      "/workers/proxy-signed-release/";

  @Test
  void requiresTheWorkerImageReleaseIdentity() {
    assertEquals(
        "worker-image-releases@conferlabs.iam.gserviceaccount.com",
        SigstoreWorkerReleaseVerifier.SIGNER);
    assertNotEquals(
        "releases@conferlabs.iam.gserviceaccount.com",
        SigstoreWorkerReleaseVerifier.SIGNER);
  }

  @Test
  void matchingSignedReleaseIsTrusted() {
    AtomicReference<byte[]> artifact = new AtomicReference<>();
    AtomicReference<String> bundle = new AtomicReference<>();
    SigstoreWorkerReleaseVerifier verifier = verifier(
        (observedArtifact, observedBundle) -> {
          artifact.set(observedArtifact);
          bundle.set(observedBundle);
        });
    String manifest = manifest();

    assertDoesNotThrow(() -> verifier.verify(manifest, BUNDLE, claims()));

    assertArrayEquals(manifest.getBytes(StandardCharsets.UTF_8), artifact.get());
    assertEquals(BUNDLE, bundle.get());
  }

  @Test
  void everySignedMeasurementMustMatchTheQuoteExactly() {
    SigstoreWorkerReleaseVerifier verifier = verifier((artifact, bundle) -> {});
    List<WorkerTdxClaims> untrustedMeasurements = List.of(
        claims(hex('f'), hex('b'), hex('c'), hex('d')),
        claims(hex('a'), hex('f'), hex('c'), hex('d')),
        claims(hex('a'), hex('b'), hex('f'), hex('d')),
        claims(hex('a'), hex('b'), hex('c'), hex('f')));

    for (WorkerTdxClaims untrusted : untrustedMeasurements) {
      assertThrows(
          WorkerException.class,
          () -> verifier.verify(manifest(), BUNDLE, untrusted));
    }
  }

  @Test
  void invalidManifestIsRejected() {
    SigstoreWorkerReleaseVerifier verifier = verifier((artifact, bundle) -> {});
    List<String> invalidManifests = List.of(
        "",
        manifest().replace("\"version\":1", "\"version\":2"),
        manifest().replace("\"confer-worker-image\"", "\"proxy\""),
        manifest().replace(hex('c'), "f".repeat(95)),
        manifest().replace("\"rtmr0\":\"" + hex('b'),
                           "\"platformMeasurement\":\"" + hex('b')),
        manifest().replace("\"tdxMeasurements\":{",
                           "\"measurements\":{"));

    for (String invalid : invalidManifests) {
      assertThrows(
          WorkerException.class,
          () -> verifier.verify(invalid, BUNDLE, claims()));
    }
  }

  @Test
  void invalidSignatureIsRejectedBeforeManifestParsing() {
    WorkerException signatureFailure = new WorkerException("invalid signature");
    SigstoreWorkerReleaseVerifier verifier = verifier((artifact, bundle) -> {
      throw signatureFailure;
    });

    assertEquals(
        signatureFailure,
        assertThrows(
            WorkerException.class,
            () -> verifier.verify("not json", BUNDLE, claims())));
  }

  @Test
  void productionAndStagingTrustedRootsAreBundled() {
    ObjectMapper mapper = new ObjectMapper();

    assertDoesNotThrow(
        () -> new SigstoreWorkerReleaseVerifier(mapper, false));
    assertDoesNotThrow(
        () -> new SigstoreWorkerReleaseVerifier(mapper, true));
  }

  @Test
  void configuredTrustedRootLoads() {
    assertDoesNotThrow(
        () -> new SigstoreWorkerReleaseVerifier(new ObjectMapper()));
  }

  @Test
  void verifiesAPublishedWorkerReleaseBundleOffline() throws Exception {
    ObjectMapper mapper = new ObjectMapperProducer().produceObjectMapper();
    String manifest = fixture("manifest.json");
    String bundle = fixture("manifest.bundle.json");
    WorkerTdxClaims claims = claims(mapper, manifest);
    SigstoreWorkerReleaseVerifier staging =
        new SigstoreWorkerReleaseVerifier(mapper, true);

    assertDoesNotThrow(() -> staging.verify(manifest, bundle, claims));

    String modified = manifest.replace(
        "\"imageVersion\":\"0.1.0-RC11\"",
        "\"imageVersion\":\"0.1.0-RC12\"");
    assertThrows(
        WorkerException.class,
        () -> staging.verify(modified, bundle, claims));

    SigstoreWorkerReleaseVerifier production =
        new SigstoreWorkerReleaseVerifier(mapper, false);
    assertThrows(
        WorkerException.class,
        () -> production.verify(manifest, bundle, claims));
  }

  @Test
  void concurrentlyVerifiesValidAndTamperedBundlesWithOneVerifier()
      throws Exception
  {
    ObjectMapper mapper = new ObjectMapperProducer().produceObjectMapper();
    String manifest = fixture("manifest.json");
    String bundle = fixture("manifest.bundle.json");
    String tampered = tamper(
        mapper,
        bundle,
        "/verificationMaterial/tlogEntries/0/inclusionProof",
        "rootHash");
    WorkerTdxClaims claims = claims(mapper, manifest);
    SigstoreWorkerReleaseVerifier verifier =
        new SigstoreWorkerReleaseVerifier(mapper, true);
    int checks = 16;
    CountDownLatch ready = new CountDownLatch(checks);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(checks)) {
      List<Future<Void>> results = new ArrayList<>();
      for (int index = 0; index < checks; index++) {
        boolean valid = index % 2 == 0;
        results.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(2, TimeUnit.SECONDS));
          if (valid) {
            verifier.verify(manifest, bundle, claims);
          } else {
            assertThrows(
                WorkerException.class,
                () -> verifier.verify(manifest, tampered, claims));
          }
          return null;
        }));
      }
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      start.countDown();
      for (Future<Void> result : results) {
        assertNull(result.get(10, TimeUnit.SECONDS));
      }
    } finally {
      start.countDown();
    }
  }

  @Test
  void rejectsEveryTamperedPartOfAPublishedWorkerReleaseBundle()
      throws Exception
  {
    ObjectMapper mapper = new ObjectMapperProducer().produceObjectMapper();
    String manifest = fixture("manifest.json");
    String bundle = fixture("manifest.bundle.json");
    WorkerTdxClaims claims = claims(mapper, manifest);
    SigstoreWorkerReleaseVerifier verifier =
        new SigstoreWorkerReleaseVerifier(mapper, true);

    assertRejected(
        verifier,
        manifest,
        claims,
        tamper(mapper, bundle, "/messageSignature", "signature"));
    assertRejected(
        verifier,
        manifest,
        claims,
        tamper(mapper, bundle, "/verificationMaterial/certificate", "rawBytes"));
    assertRejected(
        verifier,
        manifest,
        claims,
        tamper(
            mapper,
            bundle,
            "/verificationMaterial/tlogEntries/0/inclusionPromise",
            "signedEntryTimestamp"));
    assertRejected(
        verifier,
        manifest,
        claims,
        tamper(
            mapper,
            bundle,
            "/verificationMaterial/tlogEntries/0/inclusionProof",
            "rootHash"));

    ObjectNode withoutTransparencyLog =
        (ObjectNode) mapper.readTree(bundle);
    ((ObjectNode) withoutTransparencyLog.path("verificationMaterial"))
        .putArray("tlogEntries");
    assertRejected(
        verifier,
        manifest,
        claims,
        mapper.writeValueAsString(withoutTransparencyLog));
  }

  @Test
  void rejectsARealBundleSignedByTheProxyReleaseIdentity()
      throws Exception
  {
    ObjectMapper mapper = new ObjectMapperProducer().produceObjectMapper();
    SigstoreWorkerReleaseVerifier verifier =
        new SigstoreWorkerReleaseVerifier(mapper, true);

    WorkerException rejection = assertThrows(
        WorkerException.class,
        () -> verifier.verify(
            fixture(PROXY_RELEASE_FIXTURE, "manifest.json"),
            fixture(PROXY_RELEASE_FIXTURE, "manifest.bundle.json"),
            claims()));

    assertEquals("Worker release signature is invalid", rejection.getMessage());
  }

  private static SigstoreWorkerReleaseVerifier verifier(
      SigstoreWorkerReleaseVerifier.BundleVerifier bundles)
  {
    return new SigstoreWorkerReleaseVerifier(new ObjectMapper(), bundles);
  }

  private static String manifest() {
    return """
        {
          "artifactType":"confer-worker-image",
          "archiveSha256":"%s",
          "imageVersion":"0.1.0-SNAPSHOT",
          "tdxMeasurements":{
            "mrtd":"%s",
            "rtmr0":"%s",
            "rtmr1":"%s",
            "rtmr2":"%s"
          },
          "version":1
        }
        """.formatted(
            "0".repeat(64),
            hex('a'),
            hex('b'),
            hex('c'),
            hex('d'));
  }

  private static WorkerTdxClaims claims() {
    return claims(hex('a'), hex('b'), hex('c'), hex('d'));
  }

  private static WorkerTdxClaims claims(ObjectMapper mapper,
                                        String       manifest)
      throws IOException
  {
    JsonNode measurements = mapper.readTree(manifest).path("tdxMeasurements");
    return claims(
        measurements.path("mrtd").textValue(),
        measurements.path("rtmr0").textValue(),
        measurements.path("rtmr1").textValue(),
        measurements.path("rtmr2").textValue());
  }

  private static String fixture(String name) throws IOException {
    return fixture(RELEASE_FIXTURE, name);
  }

  private static String fixture(String directory,
                                String name)
      throws IOException
  {
    try (InputStream input = SigstoreWorkerReleaseVerifierTest.class
        .getResourceAsStream(directory + name)) {
      if (input == null) {
        throw new IOException("Missing worker release fixture: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String tamper(ObjectMapper mapper,
                               String       bundle,
                               String       objectPointer,
                               String       field)
      throws IOException
  {
    ObjectNode document = (ObjectNode) mapper.readTree(bundle);
    ObjectNode object = (ObjectNode) document.at(objectPointer);
    String value = object.path(field).textValue();
    char replacement = value.charAt(0) == 'A' ? 'B' : 'A';
    object.put(field, replacement + value.substring(1));
    return mapper.writeValueAsString(document);
  }

  private static void assertRejected(SigstoreWorkerReleaseVerifier verifier,
                                     String                        manifest,
                                     WorkerTdxClaims               claims,
                                     String                        bundle)
  {
    assertThrows(
        WorkerException.class,
        () -> verifier.verify(manifest, bundle, claims));
  }

  private static WorkerTdxClaims claims(String mrtd,
                                        String rtmr0,
                                        String rtmr1,
                                        String rtmr2)
  {
    return new WorkerTdxClaims(
        mrtd,
        rtmr0,
        rtmr1,
        rtmr2,
        "00".repeat(64),
        false,
        false);
  }

  private static String hex(char value) {
    return Character.toString(value).repeat(96);
  }
}
