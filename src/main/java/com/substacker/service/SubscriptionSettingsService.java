package com.substacker.service;

import com.substacker.model.AppState;
import com.substacker.model.SubscriptionSettings;
import com.substacker.model.SubscriptionTemplate;
import com.substacker.proxy.SubscriptionConfigProfile;
import com.substacker.store.StateStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SubscriptionSettingsService {
  private final StateStore store;

  public SubscriptionSettingsService(StateStore store) {
    this.store = store;
  }

  public SubscriptionSettings current() {
    return store.load().subscription();
  }

  public SubscriptionSettings update(String defaultConfigId, Boolean regionalGroupsUrlTestEnabled) {
    AppState state = store.load();
    SubscriptionSettings current = state.subscription();

    String effectiveDefaultConfigId = defaultConfigId == null || defaultConfigId.isBlank()
        ? current.defaultConfigId()
        : defaultConfigId.trim();
    boolean effectiveRegionalGroupsUrlTestEnabled = regionalGroupsUrlTestEnabled == null
        ? current.regionalGroupsUrlTestEnabled()
        : regionalGroupsUrlTestEnabled;

    if (effectiveDefaultConfigId.isBlank()) {
      throw new IllegalArgumentException("defaultConfigId 不能为空");
    }
    if (!SubscriptionConfigProfile.exists(effectiveDefaultConfigId)
        && current.findTemplate(effectiveDefaultConfigId) == null) {
      throw new IllegalArgumentException("Unknown config: " + effectiveDefaultConfigId);
    }

    SubscriptionSettings updated = new SubscriptionSettings(
        effectiveDefaultConfigId,
        current.templates(),
        effectiveRegionalGroupsUrlTestEnabled
    );
    store.save(state.withSubscription(updated));
    return updated;
  }

  public String findTemplateUrl(String templateId) {
    SubscriptionTemplate template = current().findTemplate(templateId);
    return template == null ? null : template.url();
  }

  public SubscriptionSettings createTemplate(String name, String url) {
    validateTemplatePayload(name, url);

    AppState state = store.load();
    List<SubscriptionTemplate> templates = new ArrayList<>(state.subscription().templates());
    SubscriptionTemplate created = new SubscriptionTemplate(UUID.randomUUID().toString(), name, url);
    templates.add(created);

    SubscriptionSettings updated = new SubscriptionSettings(
        created.id(),
        templates,
        state.subscription().regionalGroupsUrlTestEnabled()
    );
    store.save(state.withSubscription(updated));
    return updated;
  }

  public SubscriptionSettings updateTemplate(String id, String name, String url) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("模板 ID 不能为空");
    }

    validateTemplatePayload(name, url);
    AppState state = store.load();
    List<SubscriptionTemplate> templates = new ArrayList<>(state.subscription().templates());
    boolean found = false;
    for (int i = 0; i < templates.size(); i++) {
      SubscriptionTemplate current = templates.get(i);
      if (current != null && id.equals(current.id())) {
        templates.set(i, new SubscriptionTemplate(id, name, url));
        found = true;
        break;
      }
    }
    if (!found) {
      throw new IllegalArgumentException("模板不存在: " + id);
    }

    SubscriptionSettings updated = new SubscriptionSettings(
        id,
        templates,
        state.subscription().regionalGroupsUrlTestEnabled()
    );
    store.save(state.withSubscription(updated));
    return updated;
  }

  public SubscriptionSettings deleteTemplate(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("模板 ID 不能为空");
    }

    AppState state = store.load();
    List<SubscriptionTemplate> templates = new ArrayList<>(state.subscription().templates());
    boolean removed = templates.removeIf(template -> template != null && id.equals(template.id()));
    if (!removed) {
      throw new IllegalArgumentException("模板不存在: " + id);
    }

    String nextDefault = id.equals(state.subscription().defaultConfigId())
        ? SubscriptionConfigProfile.defaultId()
        : state.subscription().defaultConfigId();
    SubscriptionSettings updated = new SubscriptionSettings(
        nextDefault,
        templates,
        state.subscription().regionalGroupsUrlTestEnabled()
    );
    store.save(state.withSubscription(updated));
    return updated;
  }

  private static void validateTemplatePayload(String name, String url) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("模板名称不能为空");
    }
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("模板 URL 不能为空");
    }
    try {
      URI uri = URI.create(url.trim());
      String scheme = uri.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
        throw new IllegalArgumentException("模板 URL 只支持 http/https");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("模板 URL 非法");
    }
  }
}
