package org.moxie.confer.proxy.images;

import java.util.Arrays;

/**
 * Recognizes the image formats the model is allowed to decode.
 *
 * vLLM hands image bytes to Pillow, which picks a decoder from the file's leading
 * bytes rather than the declared media type. The signature is therefore the only
 * reliable gate: anything outside JPEG, PNG, GIF and WebP (EPS, TIFF, BMP, XBM,
 * JPEG 2000, ...) is refused before it reaches a decoder.
 */
public final class ImageSignature {

  public static final int HEADER_LENGTH = 12;

  private static final byte[] JPEG   = new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff };
  private static final byte[] PNG    = new byte[] { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
  private static final byte[] GIF87A = new byte[] { 0x47, 0x49, 0x46, 0x38, 0x37, 0x61 };
  private static final byte[] GIF89A = new byte[] { 0x47, 0x49, 0x46, 0x38, 0x39, 0x61 };
  private static final byte[] RIFF   = new byte[] { 0x52, 0x49, 0x46, 0x46 };
  private static final byte[] WEBP   = new byte[] { 0x57, 0x45, 0x42, 0x50 };

  private static final int WEBP_FORM_OFFSET = 8;

  private ImageSignature() {}

  public static boolean isAllowed(byte[] header) {
    return matches(header, 0, JPEG)
        || matches(header, 0, PNG)
        || matches(header, 0, GIF87A)
        || matches(header, 0, GIF89A)
        || (matches(header, 0, RIFF) && matches(header, WEBP_FORM_OFFSET, WEBP));
  }

  private static boolean matches(byte[] header,
                                 int    offset,
                                 byte[] signature)
  {
    return header.length >= offset + signature.length
        && Arrays.equals(header, offset, offset + signature.length, signature, 0, signature.length);
  }
}
