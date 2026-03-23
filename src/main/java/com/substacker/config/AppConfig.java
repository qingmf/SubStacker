package com.substacker.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "substacker")
public interface AppConfig {
  @WithDefault("./data")
  String dataDir();
}

