package org.moxie.confer.proxy.util;

import java.util.function.Consumer;

public sealed interface Result<T, E> permits Success, Failure {

  void ifSuccessOrElse(Consumer<? super T> onSuccess,
                       Consumer<? super E> onFailure);

  static <T, E> Result<T, E> success(T value) {
    return new Success<>(value);
  }

  static <T, E> Result<T, E> failure(E error) {
    return new Failure<>(error);
  }
}
