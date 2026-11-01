package org.moxie.confer.proxy.images;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Names a pool of image normalizer connections. Each client websocket gets its own random key,
 * so a decoder only ever sees images from the one connection that sent them, and the key in the
 * image URLs vLLM fetches says nothing about who the user is. Clients never supply image URLs, so
 * they cannot choose which pool their images are decoded in.
 */
public final class ImagePoolKey {

  private static final SecureRandom RANDOM      = new SecureRandom();
  private static final int          KEY_BYTES   = 16;
  private static final Pattern      WELL_FORMED = Pattern.compile("^[0-9a-f]{" + (KEY_BYTES * 2) + "}$");

  private ImagePoolKey() {}

  public static String generate() {
    byte[] key = new byte[KEY_BYTES];
    RANDOM.nextBytes(key);
    return HexFormat.of().formatHex(key);
  }

  public static boolean isWellFormed(String poolKey) {
    return poolKey != null && WELL_FORMED.matcher(poolKey).matches();
  }
}
