import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { apiFetch, ApiError, UNAUTHORIZED_EVENT } from "../lib/api";
import { authResponseSchema, userSchema, type User } from "../lib/contracts";
import {
  clearAuthSession,
  readAuthSession,
  writeAuthSession,
  type AuthSession,
} from "../lib/session";
import { AuthContext } from "./useAuth";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(() => readAuthSession());
  const [checking, setChecking] = useState(() => readAuthSession() !== null);

  const logout = useCallback(() => {
    clearAuthSession();
    setSession(null);
    setChecking(false);
  }, []);

  const token = session?.token;
  useEffect(() => {
    window.addEventListener(UNAUTHORIZED_EVENT, logout);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, logout);
  }, [logout]);

  useEffect(() => {
    if (!token) return;

    const controller = new AbortController();
    apiFetch<User>("/api/users/me", {
      token,
      schema: userSchema,
      signal: controller.signal,
    })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) logout();
      })
      .finally(() => setChecking(false));

    return () => controller.abort();
  }, [logout, token]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await apiFetch("/api/auth/login", {
      method: "POST",
      body: { email, password },
      schema: authResponseSchema,
    });
    setSession(writeAuthSession(response));
  }, []);

  const signup = useCallback(
    async (email: string, password: string, nickname: string) => {
      await apiFetch("/api/auth/signup", {
        method: "POST",
        body: { email, password, nickname },
      });
      await login(email, password);
    },
    [login],
  );

  const demoLogin = useCallback(async () => {
    const response = await apiFetch("/api/auth/demo", {
      method: "POST",
      schema: authResponseSchema,
    });
    setSession(writeAuthSession(response));
  }, []);

  const value = useMemo(
    () => ({ session, checking, login, signup, demoLogin, logout }),
    [session, checking, login, signup, demoLogin, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
