package dev.minecraftagent.standalone.common;

/** A single terminal result for a Runtime-originated client tool call. */
public sealed interface ClientToolOutcome permits ClientToolResult, ClientToolError {}
