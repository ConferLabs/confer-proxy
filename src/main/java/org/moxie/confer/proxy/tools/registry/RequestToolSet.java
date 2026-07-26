package org.moxie.confer.proxy.tools.registry;

import org.moxie.confer.proxy.tools.Tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RequestToolSet {

  private static final RequestToolSet EMPTY = new RequestToolSet(Map.of());

  private final Map<String, Tool> tools;
  private final List<Tool> values;

  RequestToolSet(Map<String, Tool> tools) {
    this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    values = List.copyOf(this.tools.values());
  }

  public static RequestToolSet empty() {
    return EMPTY;
  }

  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  public Optional<Tool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<Tool> values() {
    return values;
  }
}
