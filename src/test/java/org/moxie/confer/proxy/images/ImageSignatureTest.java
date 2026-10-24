package org.moxie.confer.proxy.images;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSignatureTest {

  @Test
  void acceptsJpegPngGifAndWebp() {
    assertTrue(ImageSignature.isAllowed(new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0 }));
    assertTrue(ImageSignature.isAllowed(new byte[] { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d }));
    assertTrue(ImageSignature.isAllowed(ascii("GIF87a")));
    assertTrue(ImageSignature.isAllowed(ascii("GIF89a")));
    assertTrue(ImageSignature.isAllowed(ascii("RIFF\u0000\u0000\u0000\u0000WEBP")));
  }

  @Test
  void rejectsFormatsOutsideTheAllowlist() {
    assertFalse(ImageSignature.isAllowed(ascii("%!PS-Adobe-3.0 EPSF-3.0")));
    assertFalse(ImageSignature.isAllowed(new byte[] { 0x49, 0x49, 0x2a, 0x00, 0, 0, 0, 0 }));
    assertFalse(ImageSignature.isAllowed(new byte[] { 0x4d, 0x4d, 0x00, 0x2a, 0, 0, 0, 0 }));
    assertFalse(ImageSignature.isAllowed(ascii("BM\u0000\u0000\u0000\u0000")));
    assertFalse(ImageSignature.isAllowed(ascii("#define image_width 8")));
    assertFalse(ImageSignature.isAllowed(new byte[] { 0, 0, 0, 0x0c, 0x6a, 0x50, 0x20, 0x20, 0x0d, 0x0a, (byte) 0x87, 0x0a }));
    assertFalse(ImageSignature.isAllowed(ascii("RIFF\u0000\u0000\u0000\u0000WAVE")));
  }

  @Test
  void rejectsTruncatedHeaders() {
    assertFalse(ImageSignature.isAllowed(new byte[0]));
    assertFalse(ImageSignature.isAllowed(new byte[] { (byte) 0xff, (byte) 0xd8 }));
    assertFalse(ImageSignature.isAllowed(ascii("RIFF\u0000\u0000\u0000\u0000WEB")));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }
}
