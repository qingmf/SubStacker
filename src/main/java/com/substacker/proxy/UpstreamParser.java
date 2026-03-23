package com.substacker.proxy;

import java.util.List;

public interface UpstreamParser {
  boolean supports(String contentType, String content);

  List<ProxyNode> parse(String upstreamName, String contentType, String content);
}

