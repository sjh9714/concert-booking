/**
 * 공연 포스터.
 *
 * 사진 위에 공연명과 아티스트를 활자로 얹는다. 이미지에 굽지 않는 이유는
 * 화면 낭독기가 읽어야 하고, 어느 배율에서도 선명해야 하고, 공연이 늘어도
 * 사진만 있으면 되기 때문이다 (`scripts/fetch-posters.mjs`).
 *
 * 비율은 3:4다 — NOL 티켓 0.753, 예스24 0.71을 실측해 정했다(`DESIGN.md`).
 */
import { POSTER_SLUGS } from "../lib/posters";

type PosterProps = {
  title: string;
  artist?: string;
  /** 카드는 240, 상세는 480 */
  width?: 240 | 480;
  /** 목록 아래쪽 카드는 지연 로딩한다 */
  eager?: boolean;
};

export function Poster({ title, artist, width = 240, eager = false }: PosterProps) {
  const slug = POSTER_SLUGS[title];

  return (
    <div className={`poster poster-${width}`}>
      {slug ? (
        <picture>
          <source
            type="image/avif"
            srcSet={`/posters/${slug}-${width}.avif 1x, /posters/${slug}-720.avif 2x`}
          />
          <img
            src={`/posters/${slug}-${width}.webp`}
            srcSet={`/posters/${slug}-${width}.webp 1x, /posters/${slug}-720.webp 2x`}
            /* 포스터 사진은 분위기만 담당한다. 공연명·아티스트는 바로 아래 활자로 있으므로
               사진까지 읽으면 같은 말을 두 번 듣게 된다. */
            alt=""
            loading={eager ? "eager" : "lazy"}
            decoding="async"
            width={width}
            height={Math.round((width * 4) / 3)}
          />
        </picture>
      ) : (
        // 사진이 없는 공연도 자리는 지킨다. 자리가 비면 격자가 무너진다.
        <div className="poster-blank" aria-hidden="true" />
      )}

      {/*
       * 포스터 그림에 박힌 글씨와 같은 역할이라 낭독하지 않는다. 공연명은 언제나
       * 바로 옆에 진짜 활자로 있다 — 목록은 카드 제목, 상세는 h1. 여기까지 읽으면
       * 같은 이름을 두 번 듣는다. 굽지 않고 활자로 얹는 이유는 선명함과 유지보수다.
       */}
      <div className="poster-type" aria-hidden="true">
        {artist && <span className="poster-artist">{artist}</span>}
        <strong className="poster-title">{title}</strong>
      </div>
    </div>
  );
}
