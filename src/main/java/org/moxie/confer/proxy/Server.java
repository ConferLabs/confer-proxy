package org.moxie.confer.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class Server {

  private static final Logger log = LoggerFactory.getLogger(Server.class);

  private Server() {}

  public static void main(String[] argv) {
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();

    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
      log.error("Uncaught exception in thread {}", thread.getName(), throwable);
    });

    io.helidon.microprofile.server.Server.create().start();
    log.info("Server is running...");
  }

}
