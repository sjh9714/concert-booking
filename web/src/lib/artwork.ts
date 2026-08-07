/**
 * 공연명 → 그림 자산.
 *
 * 그림은 화면이 쓰는 자산이지 예매의 사실이 아니므로 API에 넣지 않고 여기서 잇는다.
 * 데모 공연이 여섯 개로 고정돼 있어 표 하나면 된다.
 *
 * 한 공연에 두 가지 그림이 있고 **성격이 다르다**:
 * - 포스터(`public/posters/`) — Pexels 실사 사진, 3:4
 * - 배너(`public/banners/`) — fal.ai로 생성한 일러스트, 3:1
 *
 * 배너를 생성물로 만든 이유는 `public/banners/CREDITS.md`에 적어 뒀다.
 * 파일 이름(slug)은 둘이 같다.
 *
 * 공연이 늘거나 이름이 바뀌면 `scripts/fetch-posters.mjs`·`scripts/generate-banners.mjs`와
 * 이 표를 함께 고친다. 짝이 맞지 않으면 그 공연만 그림 없이 자리만 남는다.
 */
export const POSTER_SLUGS: Record<string, string> = {
  "NOCTURNE — SEOUL": "nocturne-seoul",
  "ORBITAL WEEKEND": "orbital-weekend",
  종이비행기: "paper-plane",
  "LOW TIDE": "low-tide",
  "밤의 라디오": "night-radio",
  "CROSSFADE 2026": "crossfade-2026",
};

/**
 * 배너 왼쪽(활자가 놓이는 자리)의 밝기.
 *
 * 눈으로 정하지 않고 그림의 왼쪽 45% · 세로 가운데 60% 평균 휘도를 재서 골랐다.
 * 흰 글자 대비 5.7~16.8:1, 검은 글자 대비 10.0~14.2:1이 나온 쪽을 쓴다.
 *
 * 평균만으로는 부족하다 — 색종이나 해 같은 밝은 점이 국소적으로 대비를 깨므로
 * CSS가 같은 방향의 스크림을 함께 깐다(`.banner-scrim`).
 */
export type BannerTone = "dark" | "light";

export const BANNER_TONES: Record<string, BannerTone> = {
  "NOCTURNE — SEOUL": "dark",
  "ORBITAL WEEKEND": "dark",
  종이비행기: "light",
  "LOW TIDE": "light",
  "밤의 라디오": "dark",
  "CROSSFADE 2026": "dark",
};
