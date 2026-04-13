package com.substacker.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.substacker.model.UpstreamType;
import com.substacker.service.SubscriptionService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/preview")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PreviewResource {
  private final SubscriptionService subscriptionService;

  public PreviewResource(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @POST
  public PreviewResponse preview(PreviewRequest req) {
    String yaml = subscriptionService.previewFromBody(
        req.upstreamName(),
        req.type() == null ? UpstreamType.CLASH_YAML_URL : req.type(),
        req.body() == null ? "" : req.body(),
        req.contentType(),
        req.config()
    );
    return new PreviewResponse(yaml);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PreviewRequest(String upstreamName, UpstreamType type, String contentType, String body, String config) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PreviewResponse(String yaml) {
  }
}
