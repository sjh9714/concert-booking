import { lazy, Suspense } from "react";
import { Outlet, Route, Routes } from "react-router-dom";
import { AppHeader } from "./components/AppHeader";

const AuthPage = lazy(() => import("./pages/AuthPage").then((module) => ({ default: module.AuthPage })));
const CatalogPage = lazy(() => import("./pages/CatalogPage").then((module) => ({ default: module.CatalogPage })));
const ConcertPage = lazy(() => import("./pages/ConcertPage").then((module) => ({ default: module.ConcertPage })));
const NotFoundPage = lazy(() => import("./pages/NotFoundPage").then((module) => ({ default: module.NotFoundPage })));
const QueuePage = lazy(() => import("./pages/QueuePage").then((module) => ({ default: module.QueuePage })));
const ReservationPage = lazy(() => import("./pages/ReservationPage").then((module) => ({ default: module.ReservationPage })));
const ReservationsPage = lazy(() => import("./pages/ReservationsPage").then((module) => ({ default: module.ReservationsPage })));
const SeatsPage = lazy(() => import("./pages/SeatsPage").then((module) => ({ default: module.SeatsPage })));

function Layout() {
  return (
    <>
      <a className="skip-link" href="#main-content">본문 바로가기</a>
      <AppHeader />
      <Outlet />
      <footer className="site-footer">
        <span>TICKETLINE</span>
        {/* 결제가 실제가 아니라는 사실은 반드시 남긴다. 그건 설명이 아니라 고지다 */}
        <p>테스트 환경입니다. 실제 결제와 발권은 이루어지지 않습니다.</p>
      </footer>
    </>
  );
}

export function App() {
  return (
    <Suspense fallback={<main id="main-content" className="loading-state" role="status">화면을 준비하는 중</main>}>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<CatalogPage />} />
          <Route path="login" element={<AuthPage mode="login" />} />
          <Route path="signup" element={<AuthPage mode="signup" />} />
          <Route path="concerts/:concertId" element={<ConcertPage />} />
          <Route path="queue/:scheduleId" element={<QueuePage />} />
          <Route path="seats/:scheduleId" element={<SeatsPage />} />
          <Route path="reservations" element={<ReservationsPage />} />
          <Route path="reservations/:reservationId" element={<ReservationPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
