package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppState(
    int version,
    List<Upstream> upstreams,
    SubscriptionSettings subscription
) {
  public static final int CURRENT_VERSION = 2;

  public AppState {
    version = Math.max(version, CURRENT_VERSION);
    upstreams = upstreams == null ? new ArrayList<>() : new ArrayList<>(upstreams);
    subscription = subscription == null ? SubscriptionSettings.defaults() : subscription;
  }

  public static AppState empty() {
    return new AppState(CURRENT_VERSION, new ArrayList<>(), SubscriptionSettings.defaults());
  }

  public AppState withSubscription(SubscriptionSettings subscription) {
    return new AppState(version, upstreams, subscription);
  }
}
