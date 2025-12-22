package org.moxie.confer.proxy.producers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.config.ManifestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@ApplicationScoped
public class ManifestInfoProducer {

  private static final Logger LOG = LoggerFactory.getLogger(ManifestInfoProducer.class);

  @Inject
  private Config config;

  @Produces
  @ApplicationScoped
  public ManifestInfo produceManifestInfo() {
    try {
      ManifestInfo info = ManifestInfo.fromPaths(
        config.getManifestPath(),
        config.getManifestBundlePath()
      );
      LOG.info("Manifest info loaded from {} and {}",
               config.getManifestPath(), config.getManifestBundlePath());
      return info;
    } catch (IOException e) {
      LOG.warn("Failed to read manifest files (running outside TDX?): {}", e.getMessage());
      return new ManifestInfo();
    }
  }
}
