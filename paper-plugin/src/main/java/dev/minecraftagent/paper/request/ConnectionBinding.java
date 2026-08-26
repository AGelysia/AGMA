package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;

/** The currently attached Runtime application connection together with its server codec. */
record ConnectionBinding(AuthenticatedRuntimeConnection connection, AgentProtocolCodec codec) {}
