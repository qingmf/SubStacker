package com.substacker.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum UpstreamType {
  CLASH_YAML_URL,
  URI_LIST_URL
}

