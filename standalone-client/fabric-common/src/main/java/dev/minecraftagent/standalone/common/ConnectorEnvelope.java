package dev.minecraftagent.standalone.common;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record ConnectorEnvelope(
    UUID messageId,
    UUID requestId,
    UUID scopeId,
    String type,
    Instant timestamp,
    String nonce,
    Map<String, Object> payload) {}
