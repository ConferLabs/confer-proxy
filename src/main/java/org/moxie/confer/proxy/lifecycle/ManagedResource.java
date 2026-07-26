package org.moxie.confer.proxy.lifecycle;

public interface ManagedResource extends AutoCloseable {

  @Override
  void close();
}
