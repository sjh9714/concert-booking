/**
 * 목록 맨 위의 배너 캐러셀.
 *
 * NOL 티켓 실측(2026-08-07): 배너 1425×463(3.08:1) · 자동 전환 2500ms · 전환 300ms ·
 * 무한 루프 · 썸네일 52×52 정사각(간격 8 · 모서리 6)이 슬라이드마다 하나.
 *
 * <b>그쪽에 없는 것을 여기 더한다.</b> NOL 캐러셀에는 정지 버튼이 없고 썸네일이
 * {@code <div>}라 키보드로 닿지 않는다 — WCAG 2.2.2(Pause, Stop, Hide)와 4.1.2 위반이다.
 * 실측을 기준으로 삼되 고장난 부분까지 따라가지는 않는다.
 *
 * 라이브러리를 넣지 않는다. 무한 루프는 앞뒤에 복제 한 장씩을 두고, 끝에 닿으면
 * 전환을 끈 채로 제자리에 돌려놓는 고전적인 방법으로 만든다.
 */
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import { concertDate } from "../lib/format";
import { BANNER_TONES, POSTER_SLUGS } from "../lib/artwork";
import type { Concert } from "../lib/contracts";

/** NOL 실측 그대로 */
const SLIDE_MS = 300;
/**
 * NOL은 2500ms지만 그쪽은 슬라이드가 17장이고 우린 6장이다.
 * 5초면 한 바퀴 30초 — 읽고 판단할 시간이 있다. 자동으로 움직이는 것은 느릴수록 덜 적대적이다.
 */
const AUTOPLAY_MS = 5000;

function useReducedMotion() {
  const [reduced, setReduced] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setReduced(query.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return reduced;
}

export function BannerCarousel({ concerts }: { concerts: Concert[] }) {
  const total = concerts.length;
  const reduced = useReducedMotion();

  /*
   * 앞뒤에 복제를 한 장씩 두므로 진짜 슬라이드는 1..total이다.
   * 0은 마지막의 복제, total+1은 첫 장의 복제다.
   */
  const [index, setIndex] = useState(1);
  const [animate, setAnimate] = useState(true);
  const [stopped, setStopped] = useState(false);
  const [keyboardFocus, setKeyboardFocus] = useState(false);
  const [hidden, setHidden] = useState(false);

  const current = ((index - 1) % total + total) % total;
  /*
   * 멈추는 경우는 셋이다: 정지 버튼, 키보드 포커스가 안에 들어옴, 탭이 배경으로 감.
   *
   * **마우스를 올렸다고 멈추지 않는다.** 배너가 full-bleed로 화면 위쪽을 통째로 덮어서
   * 포인터가 그냥 거기 놓여 있는 일이 잦다 — 그때마다 멈추면 "가끔 안 넘어간다"로 느껴진다.
   * 실제로 그렇게 보고를 받았고, 재 보니 원인이 호버였다. 멈추고 싶은 사람을 위해서는
   * 눈에 보이는 정지 버튼이 있다(NOL에는 그것이 없다).
   *
   * 키보드 포커스만 멈추는 것도 이유가 있다. 탭으로 들어온 사람은 읽고 고르는 중이라
   * 화면이 바뀌면 곤란하다. 마우스로 썸네일을 누른 것은 그런 상태가 아니므로
   * `:focus-visible`로 둘을 가른다.
   *
   * 배경 탭을 멈추는 것이 특히 중요하다. 배경에서는 CSS 전환이 아예 돌지 않는데
   * setInterval은 계속 뛴다 — 실제로 탭을 두고 다른 일을 하다 돌아왔더니
   * 인덱스가 14까지 가서 배너가 화면 밖으로 나가 있었다.
   */
  const paused = stopped || keyboardFocus || hidden;

  useEffect(() => {
    const onVisibility = () => setHidden(document.hidden);
    onVisibility();
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, []);

  const advance = useCallback((delta: number) => setIndex((i) => i + delta), []);

  useEffect(() => {
    if (paused || reduced || total <= 1) return;
    const timer = setInterval(() => setIndex((i) => i + 1), AUTOPLAY_MS);
    return () => clearInterval(timer);
  }, [paused, reduced, total]);

  /*
   * 복제 칸에 닿으면 전환이 끝난 뒤 제자리로 돌려놓는다.
   *
   * `transitionend`에 기대지 않는다. 배경 탭에서는 전환이 돌지 않아 이벤트가 오지 않고,
   * 감소 모션에서는 지속 시간이 0이라 역시 오지 않는다. 둘 다 복제 칸에 걸린 채 멈춘다.
   * 타이머는 어느 쪽에서든 뛴다.
   */
  useEffect(() => {
    if (index !== 0 && index !== total + 1) return;
    const timer = setTimeout(
      () => {
        setAnimate(false);
        setIndex(index === 0 ? total : 1);
      },
      animate && !reduced ? SLIDE_MS : 0,
    );
    return () => clearTimeout(timer);
  }, [index, total, animate, reduced]);

  /*
   * 제자리로 돌려놓은 다음 프레임에 전환을 되살린다 —
   * 같은 프레임에 되살리면 그 점프가 애니메이션으로 보인다.
   */
  useEffect(() => {
    if (animate) return;
    const frame = requestAnimationFrame(() => requestAnimationFrame(() => setAnimate(true)));
    return () => cancelAnimationFrame(frame);
  }, [animate]);

  const goTo = (slide: number) => setIndex(slide + 1);

  if (total === 0) return null;

  const extended = [concerts[total - 1], ...concerts, concerts[0]];

  return (
    <section
      className="banner"
      aria-roledescription="carousel"
      aria-label="추천 공연"
      onFocus={(event) => {
        // 마우스로 누른 포커스는 멈추지 않는다. 탭으로 옮겨온 것만 멈춘다.
        if (event.target.matches(":focus-visible")) setKeyboardFocus(true);
      }}
      onBlur={() => setKeyboardFocus(false)}
    >
      <ul
        className="banner-track"
        style={{
          transform: `translateX(-${index * 100}%)`,
          transitionDuration: animate && !reduced ? `${SLIDE_MS}ms` : "0ms",
        }}
      >
        {extended.map((concert, slot) => {
          const slug = POSTER_SLUGS[concert.title];
          const tone = BANNER_TONES[concert.title] ?? "dark";
          // 복제까지 세어 지금 보이는 칸인지 판단한다
          const visible = slot === index;
          const dates = concert.nextScheduleDate
            ? concert.lastScheduleDate && concert.lastScheduleDate !== concert.nextScheduleDate
              ? `${concertDate(concert.nextScheduleDate)} – ${concertDate(concert.lastScheduleDate)}`
              : concertDate(concert.nextScheduleDate)
            : "일정 준비 중";

          return (
            <li
              // 복제가 있어 id만으로는 키가 겹친다
              key={`${concert.id}-${slot}`}
              className={`banner-slide tone-${tone}`}
              aria-hidden={!visible}
              // 보이지 않는 슬라이드로 탭이 들어가지 않게 한다
              inert={!visible}
            >
              {slug ? (
                <picture>
                  <source
                    type="image/avif"
                    media="(min-width: 1441px)"
                    srcSet={`/banners/${slug}-1920.avif`}
                  />
                  <source
                    type="image/avif"
                    media="(min-width: 961px)"
                    srcSet={`/banners/${slug}-1440.avif`}
                  />
                  <source type="image/avif" srcSet={`/banners/${slug}-960.avif`} />
                  <img
                    className="banner-art"
                    src={`/banners/${slug}-1440.webp`}
                    srcSet={`/banners/${slug}-960.webp 960w, /banners/${slug}-1440.webp 1440w, /banners/${slug}-1920.webp 1920w`}
                    sizes="100vw"
                    /* 그림은 분위기만 담당한다. 공연명은 바로 옆에 활자로 있다 */
                    alt=""
                    /* 첫 장은 이 화면의 가장 큰 요소라 먼저 받는다 */
                    loading={slot === 1 ? "eager" : "lazy"}
                    fetchPriority={slot === 1 ? "high" : "auto"}
                    decoding="async"
                    width={1440}
                    height={480}
                  />
                </picture>
              ) : (
                <div className="banner-art banner-blank" aria-hidden="true" />
              )}

              {/* 그림이 밝든 어둡든 활자가 읽히게 같은 방향의 막을 깐다 */}
              <div className="banner-scrim" aria-hidden="true" />

              <div className="banner-copy">
                <p className="eyebrow">{concert.artist}</p>
                <strong className="banner-title">{concert.title}</strong>
                <p className="banner-when">
                  {concert.venue} · {dates}
                </p>
                <Link className="primary-button" to={`/concerts/${concert.id}`}>
                  예매하기
                </Link>
              </div>
            </li>
          );
        })}
      </ul>

      <div className="banner-controls">
        <button
          className="banner-arrow"
          type="button"
          aria-label="이전 공연"
          onClick={() => advance(-1)}
        >
          <ChevronLeft aria-hidden="true" size={20} />
        </button>

        {/* 썸네일 52×52 — NOL 실측. 포스터를 정사각으로 잘라 쓴다 */}
        <ul className="banner-thumbs">
          {concerts.map((concert, slide) => {
            const slug = POSTER_SLUGS[concert.title];
            return (
              <li key={concert.id}>
                <button
                  type="button"
                  className={`banner-thumb${slide === current ? " is-current" : ""}`}
                  aria-label={`${slide + 1}번째: ${concert.title}`}
                  aria-current={slide === current ? "true" : undefined}
                  onClick={() => goTo(slide)}
                >
                  {slug && (
                    <img src={`/posters/${slug}-240.webp`} alt="" loading="lazy" decoding="async" />
                  )}
                </button>
              </li>
            );
          })}
        </ul>

        <button
          className="banner-arrow"
          type="button"
          aria-label="다음 공연"
          onClick={() => advance(1)}
        >
          <ChevronRight aria-hidden="true" size={20} />
        </button>

        {/*
         * 자동으로 움직이는 것에는 멈출 방법이 있어야 한다(WCAG 2.2.2).
         * 감소 모션에서는 애초에 자동 전환이 없으므로 버튼도 두지 않는다.
         */}
        {!reduced && (
          <button
            className="banner-play"
            type="button"
            aria-label={stopped ? "자동 넘김 재생" : "자동 넘김 정지"}
            onClick={() => setStopped((s) => !s)}
          >
            {stopped ? <Play aria-hidden="true" size={15} /> : <Pause aria-hidden="true" size={15} />}
            <span>{stopped ? "재생" : "정지"}</span>
          </button>
        )}
      </div>
    </section>
  );
}
