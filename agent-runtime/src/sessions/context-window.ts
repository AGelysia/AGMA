import type { ModelInputMessage } from "../providers/model-provider.js";
import type { ConversationMessage } from "../storage/conversation-repository.js";

export interface ContextWindowLimits {
  readonly maximumMessages: number;
  readonly maximumCharacters: number;
}

export function buildContextWindow(
  history: readonly ConversationMessage[],
  currentMessage: string,
  limits: ContextWindowLimits,
): readonly ModelInputMessage[] {
  if (
    !Number.isSafeInteger(limits.maximumMessages) ||
    limits.maximumMessages < 1 ||
    !Number.isSafeInteger(limits.maximumCharacters) ||
    limits.maximumCharacters < 1
  ) {
    throw new TypeError("Context window limits are invalid.");
  }

  const selected = [...history];
  const currentCharacters = [...currentMessage].length;
  let historyCharacters = selected.reduce(
    (total, message) => total + [...message.content].length,
    0,
  );

  while (
    selected.length > 0 &&
    (selected.length + 1 > limits.maximumMessages ||
      historyCharacters + currentCharacters > limits.maximumCharacters)
  ) {
    const firstRequestId = selected[0]?.requestId;
    while (selected[0]?.requestId === firstRequestId) {
      const removed = selected.shift();
      if (removed !== undefined) {
        historyCharacters -= [...removed.content].length;
      }
    }
  }
  return [
    ...selected.map((message) => ({ role: message.role, content: message.content }) as const),
    { role: "user", content: currentMessage },
  ];
}
