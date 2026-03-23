package com.substacker.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.substacker.proxy.ProxyNode;
import com.substacker.service.SubscriptionService;
import com.substacker.service.SubscriptionService.CollectReport;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/debug")
@Produces(MediaType.APPLICATION_JSON)
public class DebugResource {
  private final SubscriptionService subscriptionService;

  public DebugResource(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @GET
  @Path("/nodes")
  public DebugNodesResponse nodes() {
    List<ProxyNode> nodes = subscriptionService.collectAll();
    return new DebugNodesResponse(nodes.size(), nodes.stream().limit(5).map(ProxyNode::name).toList());
  }

  @GET
  @Path("/report")
  public CollectReport report() {
    return subscriptionService.collectReport();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DebugNodesResponse(int count, List<String> sampleNames) {
  }
}
