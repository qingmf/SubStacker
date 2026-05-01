package com.substacker.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class StringUtils {

  private StringUtils() {
  }

  public static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public static boolean truthy(String value) {
    if (value == null) {
      return false;
    }
    return truthyObject(value.trim());
  }

  public static boolean truthyObject(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return false;
    }
    return !normalized.equals("false")
        && !normalized.equals("0")
        && !normalized.equals("off")
        && !normalized.equals("no");
  }

  public static boolean isTlsEnabled(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "0", "false", "none", "off" -> false;
      default -> true;
    };
  }

  public static boolean isBlankValue(Object value) {
    if (value == null) {
      return true;
    }
    return value instanceof String s && s.isBlank();
  }

  public static String stringifyValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static int parseInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (Exception ignored) {
      return defaultValue;
    }
  }

  public static Integer parseInteger(String value, Integer fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public static boolean looksLikeInteger(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      Integer.parseInt(value.trim());
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  public static boolean isDigits(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public static List<String> splitCsv(String value) {
    List<String> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  public static List<String> splitComma(String value) {
    List<String> parts = new ArrayList<>();
    for (String part : value.split(",")) {
      parts.add(part.trim());
    }
    return parts;
  }

  public static String sanitizeLine(String rawLine) {
    if (rawLine == null) {
      return "";
    }
    String line = rawLine.replace("\uFEFF", "").trim();
    if (line.startsWith(";") || line.startsWith("#") || line.startsWith("//")) {
      return "";
    }
    return line;
  }

  public static boolean isQuoted(String value) {
    return value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'")));
  }

  public static String unquote(String value) {
    return isQuoted(value) ? value.substring(1, value.length() - 1) : value;
  }

  public static String extractQuotedValue(String value) {
    String trimmed = value.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return null;
  }

  public static boolean isNegated(String rawValue) {
    return rawValue != null && rawValue.trim().startsWith("!");
  }

  public static List<String> dedupeStrings(List<String> values) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      out.add(value);
    }
    return new ArrayList<>(out);
  }

  public static String rootCauseMessage(Throwable t) {
    if (t == null) return "unknown";
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }

  public static String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    return path;
  }

  public static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    return v.asText();
  }

  public static int intValue(JsonNode node, String field, int defaultValue) {
    if (node == null) {
      return defaultValue;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return defaultValue;
    }
    if (v.isNumber()) {
      return v.asInt(defaultValue);
    }
    try {
      return Integer.parseInt(v.asText());
    } catch (Exception ignored) {
      return defaultValue;
    }
  }

  public static Set<Integer> parseIntSet(String rawValue) {
    Set<Integer> out = new LinkedHashSet<>();
    String normalized = rawValue == null ? "" : rawValue.trim();
    if (normalized.startsWith("!")) {
      normalized = normalized.substring(1).trim();
    }
    for (String part : splitComma(normalized)) {
      Integer value = parseInteger(part, null);
      if (value != null) {
        out.add(value);
      }
    }
    return out;
  }

  public static Set<String> parseStringSet(String rawValue) {
    Set<String> out = new LinkedHashSet<>();
    String normalized = rawValue == null ? "" : rawValue.trim();
    if (normalized.startsWith("!")) {
      normalized = normalized.substring(1).trim();
    }
    for (String part : splitComma(normalized)) {
      if (!part.isBlank()) {
        out.add(part.trim().toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  public static boolean looksLikeHttpUrl(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      java.net.URI uri = java.net.URI.create(value.trim());
      return Objects.equals(uri.getScheme(), "http") || Objects.equals(uri.getScheme(), "https");
    } catch (Exception ignored) {
      return false;
    }
  }

  public static void putIntOrString(java.util.Map<String, Object> out, String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    try {
      out.put(key, Integer.parseInt(value));
    } catch (Exception ignored) {
      out.put(key, value);
    }
  }
}
