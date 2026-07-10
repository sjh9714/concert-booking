import { X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

const guarantees = [
  {
    title: "차례가 된 사용자만 좌석으로",
    body: "입장권은 사용자와 공연 회차에 묶이고 짧은 시간만 유효합니다. 한 번 예매에 사용한 입장권은 다시 쓸 수 없습니다.",
  },
  {
    title: "같은 좌석에는 한 명만",
    body: "요청 시점의 화면만 믿지 않고 예매 직전에 좌석을 다시 확인합니다. 좌석 점유와 결제 대기 예매 생성을 하나의 처리 경계로 묶습니다.",
  },
  {
    title: "같은 요청은 같은 결과로",
    body: "네트워크 문제로 예매나 결제를 다시 보내도 같은 요청 키라면 기존 결과를 돌려줍니다. 내용이 바뀐 재요청은 충돌로 차단합니다.",
  },
  {
    title: "취소·만료 좌석은 다시 판매 가능하게",
    body: "결제 시간이 끝나거나 사용자가 취소하면 상태 전이를 먼저 기록하고 좌석을 반환합니다. 화면은 반환 결과를 다시 조회해 최신 상태를 보여줍니다.",
  },
] as const;

export function CorrectnessDrawer() {
  const [open, setOpen] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const close = () => {
    setOpen(false);
    window.requestAnimationFrame(() => triggerRef.current?.focus());
  };

  return (
    <>
      <button
        ref={triggerRef}
        className="text-button correctness-trigger"
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="correctness-drawer"
        onClick={() => setOpen(true)}
      >
        예매가 안전한 이유
      </button>
      {createPortal(
        <dialog
          ref={dialogRef}
          id="correctness-drawer"
          className="correctness-dialog"
          aria-labelledby="correctness-title"
          onCancel={(event) => {
            event.preventDefault();
            close();
          }}
        >
          <div className="correctness-heading">
            <div>
              <p className="eyebrow">HOW IT STAYS CORRECT</p>
              <h2 id="correctness-title">예매 흐름이 정확성을 지키는 법</h2>
            </div>
            <button
              className="drawer-close"
              type="button"
              aria-label="정확성 설명 닫기"
              onClick={close}
            >
              <X aria-hidden="true" />
            </button>
          </div>
          <p className="correctness-intro">
            화면의 빠른 흐름 뒤에서 어떤 약속을 지키는지, 사용자 관점의 네 경계만 설명합니다.
          </p>
          <ol className="correctness-list">
            {guarantees.map((guarantee, index) => (
              <li key={guarantee.title}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <div>
                  <h3>{guarantee.title}</h3>
                  <p>{guarantee.body}</p>
                </div>
              </li>
            ))}
          </ol>
        </dialog>,
        document.body,
      )}
    </>
  );
}
