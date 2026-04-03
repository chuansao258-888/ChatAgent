const PENDING_TURN_KEY_PREFIX = "chatagent:pending-turn:";

function buildKey(sessionId: string): string {
  return `${PENDING_TURN_KEY_PREFIX}${sessionId}`;
}

export function getPendingTurnId(sessionId: string): string | null {
  try {
    return sessionStorage.getItem(buildKey(sessionId));
  } catch {
    return null;
  }
}

export function setPendingTurnId(sessionId: string, turnId: string): void {
  try {
    sessionStorage.setItem(buildKey(sessionId), turnId);
  } catch {
    // Ignore storage failures and continue with in-memory state.
  }
}

export function clearPendingTurnId(sessionId: string): void {
  try {
    sessionStorage.removeItem(buildKey(sessionId));
  } catch {
    // Ignore storage failures and continue with in-memory state.
  }
}
