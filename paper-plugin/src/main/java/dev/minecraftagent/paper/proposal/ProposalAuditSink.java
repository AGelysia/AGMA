package dev.minecraftagent.paper.proposal;

/**
 * Synchronous durable audit boundary. Returning from {@link #append} means the event is persisted;
 * throwing fails closed before a proposed operation is executed.
 */
@FunctionalInterface
public interface ProposalAuditSink {
  void append(ProposalAuditEvent event);
}
