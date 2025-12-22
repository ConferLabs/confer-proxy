package org.moxie.confer.proxy;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.moxie.confer.proxy.config.Config;

import java.io.IOException;

@Provider
@Priority(1000)
public class CorsFilter implements ContainerResponseFilter {

  @Inject
  private Config config;

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
    String origin = requestContext.getHeaderString("Origin");

    if (origin != null && !origin.isEmpty() && config.getAllowedOrigins().contains(origin)) {
      responseContext.getHeaders().add("Access-Control-Allow-Origin", origin);
    } else {
      responseContext.getHeaders().add("Access-Control-Allow-Origin", config.getSiteUrl());
    }

    responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");
    responseContext.getHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin");
    responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH");
    responseContext.getHeaders().add("Access-Control-Max-Age", "3600");

    // Handle preflight requests
    if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
        responseContext.setStatus(Response.Status.OK.getStatusCode());
    }
  }
}