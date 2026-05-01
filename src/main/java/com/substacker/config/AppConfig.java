package com.substacker.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "substacker")
public interface AppConfig {
  @WithDefault("./data")
  String dataDir();

  @WithDefault("PT5S")
  Duration httpConnectTimeout();

  @WithDefault("PT5S")
  Duration httpRequestTimeout();

  @WithDefault("PT1H")
  Duration downloadCacheTtl();
}

