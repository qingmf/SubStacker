package com.substacker;

import com.substacker.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;

import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class AppLifecycle {

  void onStart(@Observes StartupEvent event, AppConfig config) {
    Path dataDir = Path.of(config.dataDir());
    try {
      Files.createDirectories(dataDir);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create data dir: " + dataDir, e);
    }
  }
}

