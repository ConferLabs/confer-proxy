package org.moxie.confer.proxy.util;

import java.util.Objects;
import java.util.function.Consumer;

record Success<T, E>(T value) implements Result<T, E> {

  Success {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public void ifSuccessOrElse(Consumer<? super T> onSuccess,
                              Consumer<? super E> onFailure)
  {
    Objects.requireNonNull(onSuccess, "onSuccess");
    Objects.requireNonNull(onFailure, "onFailure");
    onSuccess.accept(value);
  }
}
