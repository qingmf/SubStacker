package com.substacker.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.substacker.util.NameUtils;
import com.substacker.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class UriListParser implements UpstreamParser {
  private static final List<String> SUPPORTED_SCHEMES = List.of(
      "ss://",
      "ssr://",
      "vmess://",
      "trojan://",
      "vless://",
      "hysteria2://",
      "hy2://",
      "tuic://"
  );

  private final ObjectMapper objectMapper;

  public UriListParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(String contentType, String content) {
    String trimmed = content == null ? "" : content.trim();
    if (containsKnownScheme(trimmed)) {
      return true;
    }
    return looksLikeBase64(trimmed);
  }

  @Override
  public List<ProxyNode> parse(String upstreamName, String contentType, String content) {
    Objects.requireNonNull(content, "content");
    String decoded = maybeDecodeBase64(content.trim());
    List<ProxyNode> out = new ArrayList<>();
    decoded.lines().forEach(line -> {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) {
        return;
      }
      ProxyNode node = parseLine(upstreamName, s);
      if (node != null) {
        out.add(node);
      }
    });
    return out;
  }

  private ProxyNode parseLine(String upstreamName, String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.startsWith("ssr://")) {
      return parseSsr(upstreamName, line);
    }
    if (lower.startsWith("ss://")) {
      return parseSs(upstreamName, line);
    }
    if (lower.startsWith("trojan://")) {
      return parseTrojan(upstreamName, line);
    }
    if (lower.startsWith("vless://")) {
      return parseVless(upstreamName, line);
    }
    if (lower.startsWith("vmess://")) {
      return parseVmess(upstreamName, line);
    }
    if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
      return parseHysteria2(upstreamName, line);
    }
    if (lower.startsWith("tuic://")) {
      return parseTuic(upstreamName, line);
    }
    return null;
  }

  private ProxyNode parseSs(String upstreamName, String uri) {
    try {
      String noScheme = uri.substring("ss://".length());
      String fragment = null;
      int hash = noScheme.indexOf('#');
      if (hash >= 0) {
        fragment = noScheme.substring(hash + 1);
        noScheme = noScheme.substring(0, hash);
      }
      String rawQuery = null;
      int q = noScheme.indexOf('?');
      if (q >= 0) {
        rawQuery = noScheme.substring(q + 1);
        noScheme = noScheme.substring(0, q);
      }

      String userInfo;
      String hostPort;
      int at = noScheme.lastIndexOf('@');
      if (at >= 0) {
        userInfo = decodeSsUserInfo(noScheme.substring(0, at));
        hostPort = noScheme.substring(at + 1);
      } else {
        String decoded = decodeBase64Text(noScheme);
        int decodedAt = decoded.lastIndexOf('@');
        if (decodedAt < 0) {
          return null;
        }
        userInfo = decoded.substring(0, decodedAt);
        hostPort = decoded.substring(decodedAt + 1);
      }

      int colon1 = userInfo.indexOf(':');
      if (colon1 <= 0) {
        return null;
      }
      String method = userInfo.substring(0, colon1).trim();
      String password = NameUtils.urlDecode(userInfo.substring(colon1 + 1));
      HostPort authority = parseHostPort(hostPort, 8388);
      if (method.isBlank() || password.isBlank()) {
        return null;
      }

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, fragment != null ? NameUtils.urlDecode(fragment) : authority.host()));
      proxy.put("type", "ss");
      proxy.put("server", authority.host());
      proxy.put("port", authority.port());
      proxy.put("cipher", method);
      proxy.put("password", password);
      Map<String, String> query = parseQuery(rawQuery);
      applySsPlugin(proxy, query.get("plugin"));
      if (StringUtils.truthy(StringUtils.firstNonBlank(query.get("uot"), query.get("udp-over-tcp")))) {
        proxy.put("udp-over-tcp", true);
      }

      String key = ("ss|" + authority.host() + "|" + authority.port() + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseSsr(String upstreamName, String uri) {
    try {
      String payload = uri.substring("ssr://".length()).trim();
      String decoded = decodeBase64Text(payload);
      String main = decoded;
      String rawQuery = null;
      int queryStart = decoded.indexOf("/?");
      if (queryStart >= 0) {
        main = decoded.substring(0, queryStart);
        rawQuery = decoded.substring(queryStart + 2);
      }

      String[] parts = main.split(":", 6);
      if (parts.length < 6) {
        return null;
      }

      String host = NameUtils.urlDecode(parts[0]).trim();
      int port = StringUtils.parseInt(parts[1], 443);
      String protocol = parts[2].trim();
      String method = parts[3].trim();
      String obfs = parts[4].trim();
      String password = decodeBase64Text(parts[5]);
      if (host.isBlank() || password.isBlank()) {
        return null;
      }

      Map<String, String> query = parseQuery(rawQuery);
      String nameRaw = decodeOptionalBase64(query.get("remarks"));

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, StringUtils.firstNonBlank(nameRaw, host)));
      proxy.put("type", "ssr");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("cipher", method);
      proxy.put("password", password);
      proxy.put("protocol", protocol);
      proxy.put("obfs", obfs);

      String protocolParam = decodeOptionalBase64(StringUtils.firstNonBlank(query.get("protoparam"), query.get("protocol-param")));
      if (protocolParam != null && !protocolParam.isBlank()) {
        proxy.put("protocol-param", protocolParam);
      }
      String obfsParam = decodeOptionalBase64(StringUtils.firstNonBlank(query.get("obfsparam"), query.get("obfs-param")));
      if (obfsParam != null && !obfsParam.isBlank()) {
        proxy.put("obfs-param", obfsParam);
      }

      String key = ("ssr|" + host + "|" + port + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseTrojan(String upstreamName, String uri) {
    try {
      ParsedLink parsed = parseStandardLink(uri, "trojan://", 443);
      if (parsed.userInfo() == null || parsed.userInfo().isBlank()) {
        return null;
      }
      String password = NameUtils.urlDecode(parsed.userInfo());
      String host = parsed.host();
      int port = parsed.port();
      Map<String, String> q = parseQuery(parsed.rawQuery());
      String name = NameUtils.decorateName(upstreamName, NameUtils.fragmentOrHost(parsed.rawFragment(), host));

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", name);
      proxy.put("type", "trojan");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("password", password);
      String sni = StringUtils.firstNonBlank(q.get("sni"), q.get("peer"));
      if (sni != null && !sni.isBlank()) {
        proxy.put("sni", sni);
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", StringUtils.splitCsv(q.get("alpn")));
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("skip-cert-verify"), q.get("allowInsecure"), q.get("insecure")))) {
        proxy.put("skip-cert-verify", true);
      }
      String fingerprint = q.get("fp");
      if (fingerprint != null && !fingerprint.isBlank()) {
        proxy.put("client-fingerprint", fingerprint);
      }

      String network = normalizeNetwork(q.get("type"), q.get("headerType"));
      applyTransport(proxy, network, q.get("host"), q.get("path"), q.get("serviceName"), q.get("mode"));

      String key = ("trojan|" + host + "|" + port + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode(name, proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseVmess(String upstreamName, String uri) {
    try {
      String payload = uri.substring("vmess://".length()).trim();
      String json = tryDecodeBase64Text(payload);
      if (json != null) {
        try {
          JsonNode n = objectMapper.readTree(json);
          ProxyNode node = parseVmessJson(upstreamName, n);
          if (node != null) {
            return node;
          }
        } catch (Exception ignored) {
          // fall back to URI-style VMess links
        }
      }
      return parseVmessUri(upstreamName, uri);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseVmessJson(String upstreamName, JsonNode n) {
    String host = StringUtils.firstNonBlank(StringUtils.text(n, "add"), StringUtils.text(n, "server"));
    String uuid = StringUtils.firstNonBlank(StringUtils.text(n, "id"), StringUtils.text(n, "uuid"));
    if (host == null || host.isBlank() || uuid == null || uuid.isBlank()) {
      return null;
    }

    int port = StringUtils.intValue(n, "port", 443);
    int alterId = StringUtils.intValue(n, "aid", 0);
    String nameRaw = StringUtils.firstNonBlank(StringUtils.text(n, "ps"), StringUtils.text(n, "name"), host);
    String network = normalizeNetwork(StringUtils.text(n, "net"), StringUtils.text(n, "type"));
    String hostHeader = StringUtils.firstNonBlank(StringUtils.text(n, "host"), StringUtils.text(n, "authority"));
    String path = StringUtils.text(n, "path");
    String serviceName = StringUtils.firstNonBlank(StringUtils.text(n, "serviceName"), StringUtils.text(n, "service-name"));

    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("name", NameUtils.decorateName(upstreamName, nameRaw));
    proxy.put("type", "vmess");
    proxy.put("server", host);
    proxy.put("port", port);
    proxy.put("uuid", uuid);
    proxy.put("alterId", alterId);
    proxy.put("cipher", StringUtils.firstNonBlank(StringUtils.text(n, "scy"), StringUtils.text(n, "cipher"), "auto"));

    String security = StringUtils.firstNonBlank(StringUtils.text(n, "tls"), StringUtils.text(n, "security"));
    if (StringUtils.isTlsEnabled(security)) {
      proxy.put("tls", true);
    }
    String serverName = StringUtils.firstNonBlank(StringUtils.text(n, "sni"), StringUtils.text(n, "servername"));
    if (serverName != null && !serverName.isBlank()) {
      proxy.put("servername", serverName);
    }
    if (StringUtils.truthy(StringUtils.text(n, "allowInsecure")) || StringUtils.truthy(StringUtils.text(n, "skip-cert-verify"))) {
      proxy.put("skip-cert-verify", true);
    }
    String fingerprint = StringUtils.firstNonBlank(StringUtils.text(n, "fp"), StringUtils.text(n, "fingerprint"));
    if (fingerprint != null && !fingerprint.isBlank()) {
      proxy.put("client-fingerprint", fingerprint);
    }
    String alpn = StringUtils.text(n, "alpn");
    if (alpn != null && !alpn.isBlank()) {
      proxy.put("alpn", StringUtils.splitCsv(alpn));
    }

    applyTransport(proxy, network, hostHeader, path, serviceName, StringUtils.text(n, "mode"));

    String key = ("vmess|" + host + "|" + port + "|" + uuid).toLowerCase(Locale.ROOT);
    return new ProxyNode((String) proxy.get("name"), proxy, key);
  }

  private ProxyNode parseVmessUri(String upstreamName, String uri) {
    try {
      ParsedLink parsed = parseStandardLink(uri, "vmess://", 443);
      String uuid = StringUtils.firstNonBlank(parsed.userInfo(), null);
      if (uuid == null || uuid.isBlank()) {
        return null;
      }

      Map<String, String> q = parseQuery(parsed.rawQuery());
      String host = parsed.host();
      int port = parsed.port();

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, NameUtils.fragmentOrHost(parsed.rawFragment(), host)));
      proxy.put("type", "vmess");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("uuid", uuid);
      proxy.put("alterId", StringUtils.parseInt(StringUtils.firstNonBlank(q.get("alterId"), q.get("aid")), 0));
      proxy.put("cipher", StringUtils.firstNonBlank(q.get("cipher"), q.get("encryption"), q.get("scy"), "auto"));

      String security = StringUtils.firstNonBlank(q.get("security"), q.get("tls"));
      if (StringUtils.isTlsEnabled(security)) {
        proxy.put("tls", true);
      }
      String serverName = StringUtils.firstNonBlank(q.get("sni"), q.get("servername"));
      if (serverName != null && !serverName.isBlank()) {
        proxy.put("servername", serverName);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("allowInsecure"), q.get("insecure"), q.get("skip-cert-verify")))) {
        proxy.put("skip-cert-verify", true);
      }
      String fingerprint = q.get("fp");
      if (fingerprint != null && !fingerprint.isBlank()) {
        proxy.put("client-fingerprint", fingerprint);
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", StringUtils.splitCsv(q.get("alpn")));
      }

      String network = normalizeNetwork(q.get("type"), q.get("headerType"));
      applyTransport(proxy, network, q.get("host"), q.get("path"), q.get("serviceName"), q.get("mode"));

      String key = ("vmess|" + host + "|" + port + "|" + uuid).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseVless(String upstreamName, String uri) {
    try {
      ParsedLink parsed = parseStandardLink(uri, "vless://", 443);
      String uuid = parsed.userInfo();
      if (uuid == null || uuid.isBlank()) {
        return null;
      }

      Map<String, String> q = parseQuery(parsed.rawQuery());
      String host = parsed.host();
      int port = parsed.port();

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, NameUtils.fragmentOrHost(parsed.rawFragment(), host)));
      proxy.put("type", "vless");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("uuid", uuid);

      String security = StringUtils.firstNonBlank(q.get("security"), q.get("tls"));
      if (StringUtils.isTlsEnabled(security)) {
        proxy.put("tls", true);
      }
      String serverName = StringUtils.firstNonBlank(q.get("sni"), q.get("servername"), q.get("peer"));
      if (serverName != null && !serverName.isBlank()) {
        proxy.put("servername", serverName);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("allowInsecure"), q.get("insecure"), q.get("skip-cert-verify")))) {
        proxy.put("skip-cert-verify", true);
      }
      String flow = q.get("flow");
      if (flow != null && !flow.isBlank()) {
        proxy.put("flow", flow);
      }
      String packetEncoding = StringUtils.firstNonBlank(q.get("packetEncoding"), q.get("packet-encoding"));
      if (packetEncoding != null && !packetEncoding.isBlank()) {
        proxy.put("packet-encoding", packetEncoding);
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", StringUtils.splitCsv(q.get("alpn")));
      }
      String fingerprint = q.get("fp");
      if (fingerprint != null && !fingerprint.isBlank()) {
        proxy.put("client-fingerprint", fingerprint);
      }
      if ("reality".equalsIgnoreCase(security)) {
        Map<String, Object> realityOpts = new LinkedHashMap<>();
        String publicKey = StringUtils.firstNonBlank(q.get("pbk"), q.get("public-key"));
        if (publicKey != null && !publicKey.isBlank()) {
          realityOpts.put("public-key", publicKey);
        }
        String shortId = StringUtils.firstNonBlank(q.get("sid"), q.get("short-id"));
        if (shortId != null && !shortId.isBlank()) {
          realityOpts.put("short-id", shortId);
        }
        if (!realityOpts.isEmpty()) {
          proxy.put("reality-opts", realityOpts);
        }
      }

      String network = normalizeNetwork(q.get("type"), q.get("headerType"));
      applyTransport(proxy, network, q.get("host"), q.get("path"), q.get("serviceName"), q.get("mode"));

      String key = ("vless|" + host + "|" + port + "|" + uuid).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseHysteria2(String upstreamName, String uri) {
    try {
      String scheme = uri.toLowerCase(Locale.ROOT).startsWith("hy2://") ? "hy2://" : "hysteria2://";
      ParsedLink parsed = parseStandardLink(uri, scheme, 443);
      Map<String, String> q = parseQuery(parsed.rawQuery());

      String password = StringUtils.firstNonBlank(parsed.userInfo(), q.get("password"), q.get("auth"));
      if (password == null || password.isBlank()) {
        return null;
      }

      String host = parsed.host();
      int port = parsed.port();
      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, NameUtils.fragmentOrHost(parsed.rawFragment(), host)));
      proxy.put("type", "hysteria2");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("password", password);

      String sni = StringUtils.firstNonBlank(q.get("sni"), q.get("peer"));
      if (sni != null && !sni.isBlank()) {
        proxy.put("sni", sni);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("allowInsecure"), q.get("insecure"), q.get("skip-cert-verify")))) {
        proxy.put("skip-cert-verify", true);
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", StringUtils.splitCsv(q.get("alpn")));
      }
      String obfs = q.get("obfs");
      if (obfs != null && !obfs.isBlank()) {
        proxy.put("obfs", obfs);
      }
      String obfsPassword = StringUtils.firstNonBlank(q.get("obfs-password"), q.get("obfs_password"));
      if (obfsPassword != null && !obfsPassword.isBlank()) {
        proxy.put("obfs-password", obfsPassword);
      }
      String up = StringUtils.firstNonBlank(q.get("up"), q.get("upmbps"));
      if (up != null && !up.isBlank()) {
        proxy.put("up", up);
      }
      String down = StringUtils.firstNonBlank(q.get("down"), q.get("downmbps"));
      if (down != null && !down.isBlank()) {
        proxy.put("down", down);
      }
      String ports = q.get("mport");
      if (ports != null && !ports.isBlank()) {
        proxy.put("ports", ports);
      }

      String key = ("hysteria2|" + host + "|" + port + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseTuic(String upstreamName, String uri) {
    try {
      ParsedLink parsed = parseStandardLink(uri, "tuic://", 443);
      String userInfo = parsed.userInfo();
      if (userInfo == null || userInfo.isBlank()) {
        return null;
      }
      int colon = userInfo.indexOf(':');
      if (colon <= 0) {
        return null;
      }
      String uuid = userInfo.substring(0, colon);
      String password = userInfo.substring(colon + 1);
      if (uuid.isBlank() || password.isBlank()) {
        return null;
      }

      Map<String, String> q = parseQuery(parsed.rawQuery());
      String host = parsed.host();
      int port = parsed.port();

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", NameUtils.decorateName(upstreamName, NameUtils.fragmentOrHost(parsed.rawFragment(), host)));
      proxy.put("type", "tuic");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("uuid", uuid);
      proxy.put("password", password);

      String sni = StringUtils.firstNonBlank(q.get("sni"), q.get("peer"));
      if (sni != null && !sni.isBlank()) {
        proxy.put("sni", sni);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("allowInsecure"), q.get("insecure"), q.get("skip-cert-verify")))) {
        proxy.put("skip-cert-verify", true);
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", StringUtils.splitCsv(q.get("alpn")));
      }
      String congestionController = StringUtils.firstNonBlank(q.get("congestion_control"), q.get("congestion-controller"));
      if (congestionController != null && !congestionController.isBlank()) {
        proxy.put("congestion-controller", congestionController);
      }
      String udpRelayMode = StringUtils.firstNonBlank(q.get("udp_relay_mode"), q.get("udp-relay-mode"));
      if (udpRelayMode != null && !udpRelayMode.isBlank()) {
        proxy.put("udp-relay-mode", udpRelayMode);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("disable_sni"), q.get("disable-sni")))) {
        proxy.put("disable-sni", true);
      }
      if (StringUtils.truthy(StringUtils.firstNonBlank(q.get("reduce_rtt"), q.get("reduce-rtt")))) {
        proxy.put("reduce-rtt", true);
      }
      String ip = q.get("ip");
      if (ip != null && !ip.isBlank()) {
        proxy.put("ip", ip);
      }
      StringUtils.putIntOrString(proxy, "request-timeout", StringUtils.firstNonBlank(q.get("request_timeout"), q.get("request-timeout")));
      StringUtils.putIntOrString(proxy, "heartbeat-interval", StringUtils.firstNonBlank(q.get("heartbeat_interval"), q.get("heartbeat-interval")));

      String key = ("tuic|" + host + "|" + port + "|" + uuid).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String maybeDecodeBase64(String content) {
    String trimmed = content.trim();
    if (containsKnownScheme(trimmed)) {
      return content;
    }
    if (!looksLikeBase64(trimmed)) {
      return content;
    }
    try {
      String decoded = decodeBase64Text(trimmed);
      if (containsKnownScheme(decoded)) {
        return decoded;
      }
      return content;
    } catch (Exception ignored) {
      return content;
    }
  }

  private static boolean looksLikeBase64(String s) {
    if (s == null || s.isBlank()) {
      return false;
    }
    if (s.contains("://")) {
      return false;
    }
    if (s.length() < 40) {
      return false;
    }
    return s.matches("^[A-Za-z0-9+/=_\\-\\r\\n]+$");
  }

  private static String padBase64(String s) {
    String normalized = s.replace('-', '+').replace('_', '/').replace("\r", "").replace("\n", "");
    int mod = normalized.length() % 4;
    if (mod == 0) {
      return normalized;
    }
    return normalized + "=".repeat(4 - mod);
  }

  private static String decodeBase64Text(String s) {
    return new String(Base64.getDecoder().decode(padBase64(s.trim())), StandardCharsets.UTF_8);
  }

  private static String tryDecodeBase64Text(String s) {
    try {
      return decodeBase64Text(s);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String decodeOptionalBase64(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    String decoded = tryDecodeBase64Text(s);
    return decoded == null || decoded.isBlank() ? s : decoded;
  }

  private static String decodeSsUserInfo(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String decoded = tryDecodeBase64Text(raw);
    if (decoded != null && decoded.contains(":")) {
      return decoded;
    }
    String plain = NameUtils.urlDecode(raw);
    if (plain.contains(":")) {
      return plain;
    }
    return raw;
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> out = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return out;
    }
    for (String part : rawQuery.split("&")) {
      if (part.isBlank()) {
        continue;
      }
      int eq = part.indexOf('=');
      if (eq < 0) {
        out.put(NameUtils.urlDecode(part), "");
      } else {
        out.put(NameUtils.urlDecode(part.substring(0, eq)), NameUtils.urlDecode(part.substring(eq + 1)));
      }
    }
    return out;
  }

  private static boolean containsKnownScheme(String content) {
    if (content == null || content.isBlank()) {
      return false;
    }
    String lower = content.toLowerCase(Locale.ROOT);
    for (String scheme : SUPPORTED_SCHEMES) {
      if (lower.startsWith(scheme) || lower.contains("\n" + scheme) || lower.contains("\r" + scheme)) {
        return true;
      }
    }
    return false;
  }

  private static void applySsPlugin(Map<String, Object> proxy, String pluginSpec) {
    if (pluginSpec == null || pluginSpec.isBlank()) {
      return;
    }
    String[] segments = pluginSpec.split(";");
    if (segments.length == 0 || segments[0].isBlank()) {
      return;
    }

    String rawPlugin = segments[0].trim();
    Map<String, Object> pluginOpts = new LinkedHashMap<>();
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i].trim();
      if (segment.isBlank()) {
        continue;
      }
      int eq = segment.indexOf('=');
      if (eq < 0) {
        pluginOpts.put(segment, true);
      } else {
        pluginOpts.put(segment.substring(0, eq), segment.substring(eq + 1));
      }
    }

    String plugin = rawPlugin;
    if ("obfs-local".equalsIgnoreCase(rawPlugin) || "simple-obfs".equalsIgnoreCase(rawPlugin)) {
      plugin = "obfs";
      Object mode = StringUtils.firstNonBlank(StringUtils.asString(pluginOpts.get("obfs")), StringUtils.truthy(StringUtils.asString(pluginOpts.get("tls"))) ? "tls" : null);
      if (mode != null) {
        pluginOpts.put("mode", mode);
      }
      if (pluginOpts.containsKey("obfs-host")) {
        pluginOpts.put("host", pluginOpts.remove("obfs-host"));
      }
      pluginOpts.remove("obfs");
      pluginOpts.remove("tls");
    }

    proxy.put("plugin", plugin);
    if (!pluginOpts.isEmpty()) {
      proxy.put("plugin-opts", pluginOpts);
    }
  }

  private static void applyTransport(
      Map<String, Object> proxy,
      String network,
      String host,
      String path,
      String serviceName,
      String mode
  ) {
    if (network == null || network.isBlank() || "tcp".equals(network) || "raw".equals(network)) {
      return;
    }

    switch (network) {
      case "ws" -> {
        proxy.put("network", "ws");
        Map<String, Object> wsOpts = new LinkedHashMap<>();
        wsOpts.put("path", StringUtils.normalizePath(path));
        if (host != null && !host.isBlank()) {
          wsOpts.put("headers", Map.of("Host", host));
        }
        proxy.put("ws-opts", wsOpts);
      }
      case "grpc" -> {
        proxy.put("network", "grpc");
        Map<String, Object> grpcOpts = new LinkedHashMap<>();
        String resolvedServiceName = StringUtils.firstNonBlank(serviceName, path);
        if (resolvedServiceName != null && !resolvedServiceName.isBlank()) {
          grpcOpts.put("grpc-service-name", resolvedServiceName);
        }
        if (mode != null && !mode.isBlank()) {
          grpcOpts.put("grpc-mode", mode);
        }
        if (!grpcOpts.isEmpty()) {
          proxy.put("grpc-opts", grpcOpts);
        }
      }
      case "http" -> {
        proxy.put("network", "http");
        Map<String, Object> httpOpts = new LinkedHashMap<>();
        httpOpts.put("method", "GET");
        httpOpts.put("path", List.of(StringUtils.normalizePath(path)));
        if (host != null && !host.isBlank()) {
          httpOpts.put("headers", Map.of("Host", StringUtils.splitCsv(host)));
        }
        proxy.put("http-opts", httpOpts);
      }
      case "h2" -> {
        proxy.put("network", "h2");
        Map<String, Object> h2Opts = new LinkedHashMap<>();
        if (host != null && !host.isBlank()) {
          h2Opts.put("host", StringUtils.splitCsv(host));
        }
        h2Opts.put("path", StringUtils.normalizePath(path));
        proxy.put("h2-opts", h2Opts);
      }
      default -> proxy.put("network", network);
    }
  }

  private static String normalizeNetwork(String network, String headerType) {
    String normalized = network == null ? null : network.trim().toLowerCase(Locale.ROOT);
    String header = headerType == null ? null : headerType.trim().toLowerCase(Locale.ROOT);
    if ((normalized == null || normalized.isBlank() || "tcp".equals(normalized) || "raw".equals(normalized))
        && "http".equals(header)) {
      return "http";
    }
    if ("gun".equals(normalized)) {
      return "grpc";
    }
    return normalized;
  }

  private static ParsedLink parseStandardLink(String uri, String scheme, int defaultPort) {
    String rest = uri.substring(scheme.length());
    String rawFragment = null;
    int hash = rest.indexOf('#');
    if (hash >= 0) {
      rawFragment = rest.substring(hash + 1);
      rest = rest.substring(0, hash);
    }

    String rawQuery = null;
    int q = rest.indexOf('?');
    if (q >= 0) {
      rawQuery = rest.substring(q + 1);
      rest = rest.substring(0, q);
    }

    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }

    String userInfo = null;
    String hostPort = rest;
    int at = rest.lastIndexOf('@');
    if (at >= 0) {
      userInfo = NameUtils.urlDecode(rest.substring(0, at));
      hostPort = rest.substring(at + 1);
    }

    HostPort authority = parseHostPort(hostPort, defaultPort);
    return new ParsedLink(userInfo, authority.host(), authority.port(), rawQuery, rawFragment);
  }

  private static HostPort parseHostPort(String raw, int defaultPort) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Missing host");
    }
    String value = raw.trim();
    String host = value;
    int port = defaultPort;

    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      if (end < 0) {
        throw new IllegalArgumentException("Invalid IPv6 host");
      }
      host = value.substring(1, end);
      if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
        port = StringUtils.parseInt(value.substring(end + 2), defaultPort);
      }
    } else {
      int colon = value.lastIndexOf(':');
      if (colon > 0 && colon < value.length() - 1 && StringUtils.isDigits(value.substring(colon + 1))) {
        host = value.substring(0, colon);
        port = StringUtils.parseInt(value.substring(colon + 1), defaultPort);
      }
    }

    host = NameUtils.urlDecode(host);
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Missing host");
    }
    return new HostPort(host, port);
  }

  private record HostPort(String host, int port) {
  }

  private record ParsedLink(String userInfo, String host, int port, String rawQuery, String rawFragment) {
  }
}
