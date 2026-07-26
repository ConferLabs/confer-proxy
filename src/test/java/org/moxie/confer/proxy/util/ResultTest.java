package org.moxie.confer.proxy.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultTest {

  @Test
  void successInvokesOnlySuccessConsumer() {
    List<String> successes = new ArrayList<>();
    List<String> failures = new ArrayList<>();

    Result.<String, String>success("value").ifSuccessOrElse(
        successes::add,
        failures::add);

    assertEquals(List.of("value"), successes);
    assertEquals(List.of(), failures);
  }

  @Test
  void failureInvokesOnlyFailureConsumer() {
    List<String> successes = new ArrayList<>();
    List<String> failures = new ArrayList<>();

    Result.<String, String>failure("error").ifSuccessOrElse(
        successes::add,
        failures::add);

    assertEquals(List.of(), successes);
    assertEquals(List.of("error"), failures);
  }
}
