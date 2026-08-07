/**
 * 공연 포스터 사진을 받아 3:4로 굽는다.
 *
 * 예매 화면은 포스터가 지배한다. NOL 티켓과 예스24를 재 보면 한 화면에 이미지가
 * 각각 91개·118개 있고, 포스터 비율은 0.75와 0.71로 모두 3:4다(web/DESIGN.md).
 * 우리 화면에는 이미지가 하나도 없었고, 그래서 예매 서비스로 보이지 않았다.
 *
 * **사진은 생성하지 않는다.** 여섯 장 모두 Pexels에서 받은 실제 촬영 사진이고,
 * 각각 그 공연의 성격에 맞는 장면이다. 출처는 `public/posters/CREDITS.md`에 남긴다.
 *
 * **제목과 아티스트는 이미지에 굽지 않는다.** 사진 위에 HTML 활자로 얹는다 —
 * 그래야 화면 낭독기가 읽고, 어느 배율에서도 선명하고, 공연이 늘어도 사진만 있으면 된다.
 *
 * 사용법: node scripts/fetch-posters.mjs [--force]
 */
import { mkdir, writeFile, access } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../public/posters");
/** 카드 240px · 상세 480px · 2배 화면 720px */
const WIDTHS = [240, 480, 720];
/** 3:4 — NOL 티켓 실측 0.753, 예스24 0.71 */
const RATIO = 4 / 3;

/** `slug`는 DataInitializer의 공연 제목과 짝이 맞아야 한다 */
const POSTERS = [
  {
    slug: "nocturne-seoul",
    concert: "NOCTURNE — SEOUL",
    id: "2263435",
    desc: "푸른 조명이 부챗살처럼 퍼지는 야간 공연장",
  },
  {
    slug: "orbital-weekend",
    concert: "ORBITAL WEEKEND",
    id: "15235342",
    desc: "헤드폰을 쓰고 장비를 다루는 디제이",
  },
  {
    slug: "paper-plane",
    concert: "종이비행기",
    id: "878998",
    desc: "무대에서 노래하는 사람과 그 앞의 관객",
  },
  {
    slug: "low-tide",
    concert: "LOW TIDE",
    id: "15583331",
    desc: "어두운 무대 위로 떨어지는 조명과 연주자들",
  },
  {
    slug: "night-radio",
    concert: "밤의 라디오",
    id: "272795",
    desc: "방송용 마이크가 놓인 스튜디오",
  },
  {
    slug: "crossfade-2026",
    concert: "CROSSFADE 2026",
    id: "248963",
    desc: "무대를 가득 메운 페스티벌 관객",
  },
];

const force = process.argv.includes("--force");
const fileUrl = (id) =>
  `https://images.pexels.com/photos/${id}/pexels-photo-${id}.jpeg?auto=compress&cs=tinysrgb&w=1600`;
const pageUrl = (id) => `https://www.pexels.com/photo/${id}/`;

async function exists(p) {
  try {
    await access(p);
    return true;
  } catch {
    return false;
  }
}

await mkdir(OUT, { recursive: true });

for (const poster of POSTERS) {
  const marker = path.join(OUT, `${poster.slug}-480.avif`);
  if (!force && (await exists(marker))) {
    console.log(`· ${poster.slug} 이미 있음 (--force로 강제 재생성)`);
    continue;
  }

  process.stdout.write(`↓ ${poster.slug} 받는 중… `);
  const res = await fetch(fileUrl(poster.id));
  if (!res.ok) throw new Error(`${poster.slug}: HTTP ${res.status}`);
  const input = Buffer.from(await res.arrayBuffer());
  console.log(`${(input.length / 1024).toFixed(0)}KB`);

  for (const w of WIDTHS) {
    // `position: "attention"` — 사람이나 무대가 아니라 여백이 잘리게 한다.
    // 3:4로 세게 자르므로 어디를 남기는지가 결과를 가른다.
    const base = sharp(input).resize(w, Math.round(w * RATIO), {
      fit: "cover",
      position: "attention",
    });
    await writeFile(
      path.join(OUT, `${poster.slug}-${w}.avif`),
      await base.clone().avif({ quality: 50, effort: 6 }).toBuffer(),
    );
    await writeFile(
      path.join(OUT, `${poster.slug}-${w}.webp`),
      await base.clone().webp({ quality: 72 }).toBuffer(),
    );
  }
  console.log(`  → ${poster.slug}-{240,480,720}.{avif,webp}`);
}

await writeFile(
  path.join(OUT, "CREDITS.md"),
  [
    "# 공연 포스터 사진 출처",
    "",
    "여섯 장 모두 [Pexels](https://www.pexels.com/license/)에서 받은 **실제 촬영 사진**입니다.",
    "AI로 생성한 이미지가 아닙니다.",
    "",
    "Pexels 라이선스는 상업적 사용과 수정을 허용하며 출처 표기를 요구하지 않지만,",
    "확인 가능하도록 남겨 둡니다.",
    "",
    "| 공연 | 장면 | 원본 |",
    "| --- | --- | --- |",
    ...POSTERS.map((p) => `| ${p.concert} | ${p.desc} | [Pexels #${p.id}](${pageUrl(p.id)}) |`),
    "",
    "## 이 사진들은 실제 공연 포스터가 아닙니다",
    "",
    "데모용 가상 공연이므로 진짜 포스터가 존재하지 않습니다. 분위기가 맞는 사진을 골라",
    "포스터 자리에 쓰고, 공연명과 아티스트는 **이미지에 굽지 않고 화면에서 활자로 얹습니다.**",
    "그래야 화면 낭독기가 읽을 수 있고 어느 배율에서도 선명합니다.",
    "",
    "## 생성",
    "",
    "`node scripts/fetch-posters.mjs` — 3:4로 잘라 240/480/720 폭의 AVIF·WebP로 저장합니다.",
    "비율 3:4는 NOL 티켓(0.753)과 예스24(0.71)를 실측해 정한 값입니다(`web/DESIGN.md`).",
    "",
  ].join("\n"),
);

console.log(`\n완료 → ${OUT}`);
