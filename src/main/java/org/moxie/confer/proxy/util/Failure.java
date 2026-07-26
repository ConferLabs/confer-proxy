package org.moxie.confer.proxy.util;

import java.util.Objects;
import java.util.function.Consumer;

record Failure<T, E>(E error) implements Result<T, E> {

  Failure {
    Objects.requireNonNull(error, "error");
  }

  @Override
  public void ifSuccessOrElse(Consumer<? super T> onSuccess,
                              Consumer<? super E> onFailure)
  {
    Objects.requireNonNull(onSuccess, "onSuccess");
    Objects.requireNonNull(onFailure, "onFailure");
    onFailure.accept(error);
  }
}
