/**
 * 공연명 → 포스터 파일 이름.
 *
 * 포스터는 화면이 쓰는 자산이지 백엔드가 아는 사실이 아니므로 API에 넣지 않고
 * 여기서 잇는다. 데모 공연이 여섯 개로 고정돼 있어 표 하나면 된다.
 *
 * 공연이 늘거나 이름이 바뀌면 `scripts/fetch-posters.mjs`의 `POSTERS`와
 * 이 표를 함께 고친다. 짝이 맞지 않으면 그 공연만 사진 없이 자리만 남는다
 * (`Poster.tsx`의 `poster-blank`).
 */
export const POSTER_SLUGS: Record<string, string> = {
  "NOCTURNE — SEOUL": "nocturne-seoul",
  "ORBITAL WEEKEND": "orbital-weekend",
  종이비행기: "paper-plane",
  "LOW TIDE": "low-tide",
  "밤의 라디오": "night-radio",
  "CROSSFADE 2026": "crossfade-2026",
};
