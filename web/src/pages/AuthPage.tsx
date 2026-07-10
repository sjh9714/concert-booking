import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/useAuth";

export function AuthPage({ mode }: { mode: "login" | "signup" }) {
  const { session, login, signup, demoLogin } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const next = new URLSearchParams(location.search).get("next") || "/";
  const demoMode = import.meta.env.VITE_DEMO_MODE === "true";

  if (session) return <Navigate to={next} replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email") ?? "");
    const password = String(form.get("password") ?? "");
    try {
      if (mode === "signup") {
        await signup(email, password, String(form.get("nickname") ?? ""));
      } else {
        await login(email, password);
      }
      navigate(next, { replace: true });
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "요청을 처리하지 못했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  const enterDemo = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await demoLogin();
      navigate(next, { replace: true });
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "데모 계정으로 시작하지 못했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main id="main-content" className="auth-layout">
      <section className="auth-context" aria-hidden="true">
        <p className="eyebrow">TICKETLINE</p>
        <p className="auth-number">{mode === "login" ? "01" : "02"}</p>
        <p>대기 순서부터 결제 확정까지<br />한 번의 세션으로 이어집니다.</p>
      </section>
      <section className="auth-form-wrap">
        <p className="eyebrow">{mode === "login" ? "다시 오신 것을 환영합니다" : "첫 예매 준비"}</p>
        <h1>{mode === "login" ? "로그인" : "회원가입"}</h1>
        <form className="auth-form" onSubmit={submit}>
          {mode === "signup" && (
            <label>
              닉네임
              <input name="nickname" required maxLength={100} autoComplete="nickname" />
            </label>
          )}
          <label>
            이메일
            <input name="email" type="email" required autoComplete="email" />
          </label>
          <label>
            비밀번호
            <input
              name="password"
              type="password"
              required
              minLength={8}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
            />
          </label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="primary-button full" type="submit" disabled={submitting}>
            {submitting ? "처리 중" : mode === "login" ? "로그인" : "가입하고 시작"}
          </button>
        </form>
        {mode === "login" && demoMode && (
          <button
            className="secondary-button full demo-login"
            type="button"
            disabled={submitting}
            onClick={() => void enterDemo()}
          >
            데모 계정으로 바로 시작
          </button>
        )}
        <p className="auth-switch">
          {mode === "login" ? "처음 방문하셨나요?" : "이미 계정이 있나요?"}{" "}
          <Link to={`${mode === "login" ? "/signup" : "/login"}?next=${encodeURIComponent(next)}`}>
            {mode === "login" ? "회원가입" : "로그인"}
          </Link>
        </p>
      </section>
    </main>
  );
}
