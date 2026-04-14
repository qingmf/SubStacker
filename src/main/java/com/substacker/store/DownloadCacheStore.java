package com.substacker.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.substacker.config.AppConfig;
import com.substacker.model.DownloadCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DownloadCacheStore {
  private static final String FILE_NAME = "download-cache.json";

  private final ObjectMapper objectMapper;
  private final Path cacheFile;
  private final Map<String, DownloadCache.CachedEntry> entries;

  @Inject
  public DownloadCacheStore(ObjectMapper objectMapper, AppConfig config) {
    this(objectMapper, Path.of(config.dataDir()).resolve(FILE_NAME));
  }

  public DownloadCacheStore(ObjectMapper objectMapper, Path cacheFile) {
    this.objectMapper = objectMapper;
    this.cacheFile = cacheFile;
    this.entries = load().entries();
  }

  private synchronized DownloadCache load() {
    try {
      if (!Files.exists(cacheFile)) {
        return DownloadCache.empty();
      }
      return objectMapper.readValue(cacheFile.toFile(), DownloadCache.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load download cache: " + cacheFile, e);
    }
  }

  public synchronized void save() {
    try {
      Files.createDirectories(cacheFile.getParent());
      Path tmp = cacheFile.resolveSibling(FILE_NAME + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new DownloadCache(entries));
      Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to save download cache: " + cacheFile, e);
    }
  }

  public DownloadCache.CachedEntry get(String url) {
    return entries.get(url);
  }

  public void put(String url, String body, String contentType) {
    entries.put(url, new DownloadCache.CachedEntry(body, contentType, System.currentTimeMillis()));
    save();
  }
}
