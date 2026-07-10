import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <main id="main-content" className="not-found">
      <p className="eyebrow">404 / NOT FOUND</p>
      <h1>이 좌석은<br />찾을 수 없습니다.</h1>
      <Link className="primary-button" to="/">공연 목록으로</Link>
    </main>
  );
}
