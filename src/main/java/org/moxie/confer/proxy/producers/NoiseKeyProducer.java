package org.moxie.confer.proxy.producers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

@ApplicationScoped
public class NoiseKeyProducer {

  private static final Logger log = LoggerFactory.getLogger(NoiseKeyProducer.class);

  public static final String NOISE_PROTOCOL = "Noise_XX_25519_AESGCM_SHA256";

  @Produces
  @Named("noiseServerKeyPair")
  public KeyPair produceServerKeyPair() {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("X25519");
      KeyPair keyPair = keyGen.generateKeyPair();
      
      log.info("Noise server key pair produced for " + NOISE_PROTOCOL);
      return keyPair;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("X25519 algorithm not available", e);
    }
  }
}