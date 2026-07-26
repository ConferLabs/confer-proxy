package org.moxie.confer.proxy.websocket;

import org.moxie.confer.proxy.lifecycle.ManagedResource;

@FunctionalInterface
public interface ResourceFactory<Resource extends ManagedResource, Failure extends Exception> {

  Resource open() throws Failure;
}
