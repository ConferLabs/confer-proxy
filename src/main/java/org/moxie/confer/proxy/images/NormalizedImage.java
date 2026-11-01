package org.moxie.confer.proxy.images;

import java.nio.charset.StandardCharsets;

/**
 * A normalized image's checked dimensions and the PPM header (P6, 8-bit RGB) vLLM receives in
 * front of its pixels.
 *
 * The pixels come from the sandboxed normalizer, whose output is untrusted because a decoder
 * exploit would control it. It reports a frame's width and height, then exactly
 * width * height * 3 pixel bytes. Dimensions out of range reject the image and a short body fails
 * it, and the PPM header is written here from the checked dimensions. A compromised decoder can
 * therefore choose only pixel values and in-range dimensions, which any client can do by
 * uploading an image.
 */
public final class NormalizedImage {

  public static final String MEDIA_TYPE = "image/x-portable-pixmap";

  // The served model's image processor shrinks anything larger to this many pixels and refuses
  // wider aspect ratios. The normalizer applies the same limits.
  static final long MAX_PIXELS       = 16_777_216L;
  static final long MAX_ASPECT_RATIO = 200L;

  private static final int CHANNELS = 3;

  private final long   width;
  private final long   height;
  private final byte[] ppmHeader;

  private NormalizedImage(long width, long height) {
    this.width     = width;
    this.height    = height;
    this.ppmHeader = ("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII);
  }

  /** Checks dimensions the normalizer reported. */
  static NormalizedImage of(long width, long height) throws InvalidNormalizedImageException {
    if (!isWithinBounds(width, height)) {
      throw new InvalidNormalizedImageException("Normalized image dimensions are out of range");
    }
    return new NormalizedImage(width, height);
  }

  static boolean isWithinBounds(long width, long height) {
    long longSide  = Math.max(width, height);
    long shortSide = Math.min(width, height);

    return shortSide >= 1
        && longSide <= MAX_PIXELS
        && width * height <= MAX_PIXELS
        && longSide <= shortSide * MAX_ASPECT_RATIO;
  }

  public long width() {
    return width;
  }

  public long height() {
    return height;
  }

  public byte[] ppmHeader() {
    return ppmHeader.clone();
  }

  public long pixelBytes() {
    return width * height * CHANNELS;
  }

  public long ppmLength() {
    return ppmHeader.length + pixelBytes();
  }
}
