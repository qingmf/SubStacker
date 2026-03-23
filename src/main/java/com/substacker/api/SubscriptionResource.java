package com.substacker.api;

import com.substacker.service.SubscriptionService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/sub")
public class SubscriptionResource {
  private final SubscriptionService subscriptionService;

  public SubscriptionResource(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @GET
  @Path("/openclash.yaml")
  @Produces("application/x-yaml")
  public String openClashYaml() {
    return subscriptionService.buildOpenClashYaml();
  }

  @GET
  @Path("/openclash.yml")
  @Produces("application/x-yaml")
  public String openClashYml() {
    return subscriptionService.buildOpenClashYaml();
  }

  @GET
  @Path("/health")
  @Produces(MediaType.TEXT_PLAIN)
  public String health() {
    return "ok";
  }
}

