package org.moxie.confer.proxy.websocket;

import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface ResourceStreamingOutput<Resource extends ManagedResource> {

  void write(Resource resource, OutputStream output) throws IOException;
}
