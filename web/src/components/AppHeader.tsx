import { Menu, Ticket, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, NavLink } from "react-router-dom";
import { useAuth } from "../auth/useAuth";
import { CorrectnessDrawer } from "./CorrectnessDrawer";

export function AppHeader() {
  const { session, logout } = useAuth();
  const [open, setOpen] = useState(false);

  const close = () => setOpen(false);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <header className="app-header">
      <Link className="brand" to="/" aria-label="Ticketline 홈" onClick={close}>
        <Ticket aria-hidden="true" size={20} strokeWidth={1.8} />
        <span>TICKETLINE</span>
      </Link>

      <button
        className="menu-button"
        type="button"
        aria-expanded={open}
        aria-controls="site-navigation"
        aria-label={open ? "메뉴 닫기" : "메뉴 열기"}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
      </button>

      <nav id="site-navigation" className={open ? "nav open" : "nav"} aria-label="주요 메뉴">
        <NavLink to="/" end onClick={close}>
          공연
        </NavLink>
        <CorrectnessDrawer />
        {session ? (
          <>
            <NavLink to="/reservations" onClick={close}>
              내 예매
            </NavLink>
            <span className="nav-user">{session.user.nickname}</span>
            <button
              className="text-button"
              type="button"
              onClick={() => {
                logout();
                close();
              }}
            >
              로그아웃
            </button>
          </>
        ) : (
          <NavLink className="nav-login" to="/login" onClick={close}>
            로그인
          </NavLink>
        )}
      </nav>
    </header>
  );
}
