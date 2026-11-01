package org.moxie.confer.proxy.images;

/** What the normalizer says about one image, handed from the connection's reader to its stream. */
sealed interface NormalizerEvent {

  record Header(long width, long height) implements NormalizerEvent {}

  record Pixels(byte[] bytes) implements NormalizerEvent {}

  record Done() implements NormalizerEvent {}

  record Failed() implements NormalizerEvent {}
}
