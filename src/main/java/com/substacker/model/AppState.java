package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppState(
    int version,
    List<Upstream> upstreams
) {
  public static AppState empty() {
    return new AppState(1, new ArrayList<>());
  }
}

