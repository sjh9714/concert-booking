import type { ReactNode } from "react";

export function LoadingState({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div className="loading-state" role="status">
      <span className="loading-mark" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function ErrorState({
  title = "불러오지 못했습니다",
  children,
  action,
}: {
  title?: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <section className="error-state" role="alert">
      <p className="eyebrow">문제가 발생했습니다</p>
      <h1>{title}</h1>
      <p>{children}</p>
      {action}
    </section>
  );
}
