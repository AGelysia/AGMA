package dev.minecraftagent.standalone.common;

import java.util.UUID;

/** A fresh (message identifier, nonce) pair claimed from the session replay window. */
record ConnectorMessageIdentity(UUID messageId, String nonce) {}
