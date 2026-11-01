package org.moxie.confer.proxy.images;

/** Normalized image headers for tests in other packages. */
public final class NormalizerFrames {

  private NormalizerFrames() {}

  /** A checked header for a width x height image, as the normalizer would report it. */
  public static NormalizedImage image(int width, int height) {
    try {
      return NormalizedImage.of(width, height);
    } catch (InvalidNormalizedImageException error) {
      throw new IllegalArgumentException(error);
    }
  }
}
