package com.substacker.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.substacker.model.SubscriptionSettings;
import com.substacker.model.SubscriptionTemplate;
import com.substacker.proxy.SubscriptionConfigProfile;
import com.substacker.service.SubscriptionSettingsService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Path("/api/subscription")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SubscriptionSettingsResource {
  private final SubscriptionSettingsService subscriptionSettingsService;

  public SubscriptionSettingsResource(SubscriptionSettingsService subscriptionSettingsService) {
    this.subscriptionSettingsService = subscriptionSettingsService;
  }

  @GET
  @Path("/settings")
  public SubscriptionSettingsResponse settings() {
    return responseOf(subscriptionSettingsService.current());
  }

  @PUT
  @Path("/settings")
  public SubscriptionSettingsResponse update(UpdateSubscriptionSettingsRequest request) {
    String defaultConfigId = request == null ? null : request.defaultConfigId();
    Boolean regionalGroupsUrlTestEnabled = request == null ? null : request.regionalGroupsUrlTestEnabled();
    SubscriptionSettings updated = subscriptionSettingsService.update(defaultConfigId, regionalGroupsUrlTestEnabled);
    return responseOf(updated);
  }

  @POST
  @Path("/templates")
  public SubscriptionSettingsResponse createTemplate(TemplateRequest request) {
    SubscriptionSettings updated = subscriptionSettingsService.createTemplate(
        request == null ? null : request.name(),
        request == null ? null : request.url()
    );
    return responseOf(updated);
  }

  @PUT
  @Path("/templates/{id}")
  public SubscriptionSettingsResponse updateTemplate(@PathParam("id") String id, TemplateRequest request) {
    SubscriptionSettings updated = subscriptionSettingsService.updateTemplate(
        id,
        request == null ? null : request.name(),
        request == null ? null : request.url()
    );
    return responseOf(updated);
  }

  @DELETE
  @Path("/templates/{id}")
  public SubscriptionSettingsResponse deleteTemplate(@PathParam("id") String id) {
    return responseOf(subscriptionSettingsService.deleteTemplate(id));
  }

  private static SubscriptionSettingsResponse responseOf(SubscriptionSettings settings) {
    List<ConfigOption> options = new ArrayList<>();
    Arrays.stream(SubscriptionConfigProfile.values())
        .map(profile -> new ConfigOption(profile.id(), profile.label(), profile.description(), null, false))
        .forEach(options::add);
    for (SubscriptionTemplate template : settings.templates()) {
      if (template == null) {
        continue;
      }
      options.add(new ConfigOption(
          template.id(),
          template.name(),
          "远程 subconverter 风格模板",
          template.url(),
          true
      ));
    }

    return new SubscriptionSettingsResponse(
        settings.defaultConfigId(),
        settings.regionalGroupsUrlTestEnabled(),
        options
    );
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record UpdateSubscriptionSettingsRequest(String defaultConfigId, Boolean regionalGroupsUrlTestEnabled) {
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TemplateRequest(String name, String url) {
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ConfigOption(String id, String label, String description, String url, boolean custom) {
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SubscriptionSettingsResponse(
      String defaultConfigId,
      boolean regionalGroupsUrlTestEnabled,
      List<ConfigOption> options
  ) {
  }
}
