import type { AuthResponse, QueueToken, User } from "./contracts";

const AUTH_KEY = "ticketline.auth";
const QUEUE_PREFIX = "ticketline.queue.";
const IDEMPOTENCY_PREFIX = "ticketline.idempotency.";

export type AuthSession = {
  token: string;
  user: User;
  expiresAt?: string;
};

export function readAuthSession(): AuthSession | null {
  const value = sessionStorage.getItem(AUTH_KEY);
  if (!value) return null;
  try {
    return JSON.parse(value) as AuthSession;
  } catch {
    sessionStorage.removeItem(AUTH_KEY);
    return null;
  }
}

export function writeAuthSession(response: AuthResponse): AuthSession {
  const session: AuthSession = {
    token: response.token,
    expiresAt: response.expiresAt,
    user: {
      userId: response.userId,
      email: response.email,
      nickname: response.nickname,
    },
  };
  sessionStorage.setItem(AUTH_KEY, JSON.stringify(session));
  return session;
}

export function clearAuthSession(): void {
  sessionStorage.removeItem(AUTH_KEY);
  Object.keys(sessionStorage)
    .filter((key) => key.startsWith(QUEUE_PREFIX) || key.startsWith(IDEMPOTENCY_PREFIX))
    .forEach((key) => sessionStorage.removeItem(key));
}

export function writeQueueToken(token: QueueToken): void {
  sessionStorage.setItem(`${QUEUE_PREFIX}${token.scheduleId}`, JSON.stringify(token));
}

export function readQueueToken(scheduleId: number): QueueToken | null {
  const value = sessionStorage.getItem(`${QUEUE_PREFIX}${scheduleId}`);
  if (!value) return null;
  try {
    const token = JSON.parse(value) as QueueToken;
    if (new Date(token.expiresAt).getTime() <= Date.now()) {
      clearQueueToken(scheduleId);
      return null;
    }
    return token;
  } catch {
    clearQueueToken(scheduleId);
    return null;
  }
}

export function clearQueueToken(scheduleId: number): void {
  sessionStorage.removeItem(`${QUEUE_PREFIX}${scheduleId}`);
}

export function idempotencyKey(scope: string): string {
  const key = `${IDEMPOTENCY_PREFIX}${scope}`;
  const existing = sessionStorage.getItem(key);
  if (existing) return existing;
  const created = crypto.randomUUID();
  sessionStorage.setItem(key, created);
  return created;
}

export function clearIdempotencyKey(scope: string): void {
  sessionStorage.removeItem(`${IDEMPOTENCY_PREFIX}${scope}`);
}
