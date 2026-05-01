package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.substacker.proxy.SubscriptionConfigProfile;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionSettings(
    String defaultConfigId,
    List<SubscriptionTemplate> templates,
    boolean regionalGroupsUrlTestEnabled
) {
  public SubscriptionSettings {
    templates = templates == null ? new ArrayList<>() : new ArrayList<>(templates);
    defaultConfigId = normalizeDefaultConfigId(defaultConfigId, templates);
  }

  public static SubscriptionSettings defaults() {
    return new SubscriptionSettings(SubscriptionConfigProfile.defaultId(), List.of(), false);
  }

  public SubscriptionSettings withDefaultConfigId(String defaultConfigId) {
    return new SubscriptionSettings(defaultConfigId, templates, regionalGroupsUrlTestEnabled);
  }

  public SubscriptionSettings withTemplates(List<SubscriptionTemplate> templates) {
    return new SubscriptionSettings(defaultConfigId, templates, regionalGroupsUrlTestEnabled);
  }

  public SubscriptionSettings withRegionalGroupsUrlTestEnabled(boolean enabled) {
    return new SubscriptionSettings(defaultConfigId, templates, enabled);
  }

  public SubscriptionTemplate findTemplate(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    for (SubscriptionTemplate template : templates) {
      if (template != null && id.equals(template.id())) {
        return template;
      }
    }
    return null;
  }

  private static String normalizeDefaultConfigId(String defaultConfigId, List<SubscriptionTemplate> templates) {
    String profileId = SubscriptionConfigProfile.normalizeIfExists(defaultConfigId);
    if (profileId != null) {
      return profileId;
    }
    if (defaultConfigId != null && !defaultConfigId.isBlank()) {
      String normalized = defaultConfigId.trim();
      for (SubscriptionTemplate template : templates) {
        if (template != null && normalized.equals(template.id())) {
          return normalized;
        }
      }
    }
    return SubscriptionConfigProfile.defaultId();
  }
}
