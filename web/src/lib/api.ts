import type { ZodType } from "zod";
import { errorResponseSchema } from "./contracts";
import { clearAuthSession } from "./session";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "";
export const UNAUTHORIZED_EVENT = "ticketline:unauthorized";

export function handleUnauthorized(): void {
  clearAuthSession();
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  }
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export type ApiOptions<T> = Omit<RequestInit, "body"> & {
  token?: string | null;
  body?: unknown;
  schema?: ZodType<T>;
};

export async function apiFetch<T = void>(
  path: string,
  options: ApiOptions<T> = {},
): Promise<T> {
  const { token, body, schema, headers, ...requestInit } = options;
  const response = await fetch(`${API_BASE}${path}`, {
    ...requestInit,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    if (response.status === 401) {
      handleUnauthorized();
    }
    const raw: unknown = await response.json().catch(() => ({}));
    const parsed = errorResponseSchema.safeParse(raw);
    throw new ApiError(
      response.status,
      parsed.success ? (parsed.data.code ?? "REQUEST_FAILED") : "REQUEST_FAILED",
      parsed.success
        ? (parsed.data.message ?? `요청을 처리하지 못했습니다. (${response.status})`)
        : `요청을 처리하지 못했습니다. (${response.status})`,
    );
  }

  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }

  const data: unknown = await response.json();
  return schema ? schema.parse(data) : (data as T);
}

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`;
}
