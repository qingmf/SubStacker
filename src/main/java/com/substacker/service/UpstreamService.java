package com.substacker.service;

import com.substacker.model.AppState;
import com.substacker.model.Upstream;
import com.substacker.store.StateStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class UpstreamService {
  private final StateStore store;

  public UpstreamService(StateStore store) {
    this.store = store;
  }

  public AppState state() {
    return store.load();
  }

  public List<Upstream> list() {
    return store.load().upstreams().stream()
        .sorted(Comparator.comparing(Upstream::name, Comparator.nullsLast(String::compareToIgnoreCase)))
        .toList();
  }

  public Upstream create(Upstream upstream) {
    Objects.requireNonNull(upstream, "upstream");
    String id = (upstream.id() == null || upstream.id().isBlank()) ? UUID.randomUUID().toString() : upstream.id();
    Upstream normalized = new Upstream(
        id,
        upstream.name(),
        upstream.type(),
        upstream.url(),
        upstream.enabled(),
        upstream.headers()
    );

    AppState state = store.load();
    state.upstreams().add(normalized);
    store.save(state);
    return normalized;
  }

  public Upstream update(String id, Upstream upstream) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(upstream, "upstream");

    AppState state = store.load();
    for (int i = 0; i < state.upstreams().size(); i++) {
      if (id.equals(state.upstreams().get(i).id())) {
        Upstream updated = new Upstream(
            id,
            upstream.name(),
            upstream.type(),
            upstream.url(),
            upstream.enabled(),
            upstream.headers()
        );
        state.upstreams().set(i, updated);
        store.save(state);
        return updated;
      }
    }
    throw new IllegalArgumentException("Upstream not found: " + id);
  }

  public void delete(String id) {
    AppState state = store.load();
    boolean removed = state.upstreams().removeIf(u -> id.equals(u.id()));
    if (removed) {
      store.save(state);
    }
  }
}

