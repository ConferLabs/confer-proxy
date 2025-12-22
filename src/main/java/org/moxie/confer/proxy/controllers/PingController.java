package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Path("/v1/ping")
public class PingController {

  @GET
  public String getPing() {
    return "PONG";
  }
}
