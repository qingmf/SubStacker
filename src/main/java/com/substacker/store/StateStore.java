package com.substacker.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.substacker.config.AppConfig;
import com.substacker.model.AppState;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@ApplicationScoped
public class StateStore {
  private static final String FILE_NAME = "state.json";

  private final ObjectMapper objectMapper;
  private final Path stateFile;

  public StateStore(ObjectMapper objectMapper, AppConfig config) {
    this.objectMapper = objectMapper;
    this.stateFile = Path.of(config.dataDir()).resolve(FILE_NAME);
  }

  public synchronized AppState load() {
    try {
      if (!Files.exists(stateFile)) {
        return AppState.empty();
      }
      return objectMapper.readValue(stateFile.toFile(), AppState.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load state: " + stateFile, e);
    }
  }

  public synchronized void save(AppState state) {
    try {
      Files.createDirectories(stateFile.getParent());
      Path tmp = stateFile.resolveSibling(FILE_NAME + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
      Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to save state: " + stateFile, e);
    }
  }
}

