/**
 * 공연 배너 일러스트를 만든다.
 *
 * 목록 위 캐러셀에 들어가는 3:1 배너다. NOL 티켓을 재 보면 배너는 1425×463(3.08:1)이고,
 * **글자까지 구워진 일러스트 한 장**이다 — 활성 슬라이드의 텍스트 노드가 0개고
 * `<img alt="뮤지컬 〈광화문연가〉">` 하나뿐이었다(web/DESIGN.md).
 *
 * **포스터와 성격이 다르다.** 포스터는 Pexels 실사 사진이지만(`fetch-posters.mjs`)
 * 배너는 생성한 그림이다. 실제 예매 서비스의 배너는 사진을 잘라 넣은 것이 아니라
 * 배너 비율로 그린 그림이고, 3:4 사진을 3:1로 자르면 그 흉내가 되기 때문이다.
 * 특히 우리 사진 6장 중 2장은 세로라 3:1로 자르면 얇은 띠만 남는다.
 *
 * **글자는 그림에 넣지 않는다.** FLUX의 글자는 신뢰할 수 없고, 화면 낭독기가 읽어야 하고,
 * 어느 배율에서도 선명해야 한다. 대신 주제를 오른쪽에 몰고 왼쪽을 비우게 그려서
 * 그 자리에 HTML 활자를 얹는다 — 이게 "배너 크기로 설계했다"의 실체다.
 *
 * 사용법:
 *   node scripts/generate-banners.mjs                 # 없는 것만
 *   node scripts/generate-banners.mjs --only low-tide # 한 장만 (프롬프트 시험용)
 *   node scripts/generate-banners.mjs --force         # 전부 다시
 *
 * FAL_KEY는 portfolio-backend/.env.local에서 읽는다. 저장소에 넣지 않고 로그로 찍지 않는다.
 * flux/dev는 메가픽셀 과금이라 **실제 비용이 발생한다**. 그래서 기본은 건너뛰기다.
 */
import { mkdir, writeFile, access, readFile } from "node:fs/promises";
import path from "node:path";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../public/banners");
const ENV_FILE = path.join(homedir(), "Projects/portfolio-backend/.env.local");

const MODEL = "fal-ai/flux/dev";
/** 3:1 — NOL 실측 3.08:1 */
const RATIO = 3;
/** 굽는 폭. 배너는 full-bleed라 1x만 있으면 된다 */
const WIDTHS = [960, 1440, 1920];
/** 생성 크기. 모델이 거부하면 아래로 내린다 */
const GEN_SIZES = [
  { width: 1920, height: 640 },
  { width: 1536, height: 512 },
  { width: 1152, height: 384 },
];

/*
 * 모든 프롬프트에 붙는 꼬리.
 *
 * `no text`를 여러 형태로 반복하는 이유: FLUX는 "concert"·"poster" 같은 낱말을 보면
 * 글자를 그려 넣으려 든다. 한 번만 적으면 잘 안 듣는다.
 */
const SUFFIX = [
  "ultrawide 3:1 horizontal banner composition",
  "subject occupies the right third, large calm empty negative space on the left half for typography",
  "flat vector editorial poster illustration, screen-print texture, bold simple shapes",
  "no text, no letters, no words, no numbers, no typography, no logo, no watermark, no signature",
  "not a photograph",
].join(", ");

/** `slug`는 DataInitializer의 공연 제목과, 그리고 src/lib/artwork.ts와 짝이 맞아야 한다 */
const BANNERS = [
  {
    slug: "nocturne-seoul",
    concert: "NOCTURNE — SEOUL",
    desc: "한밤의 도시 위로 무대 조명이 부챗살처럼 퍼지는 그림",
    prompt:
      "Deep midnight blue and cyan night concert banner. Stylized city skyline silhouette with a glowing domed stage and sweeping light beams fanning into the night sky on the right side. Cool luminous palette",
  },
  {
    slug: "orbital-weekend",
    concert: "ORBITAL WEEKEND",
    desc: "궤도 고리와 신스 파형이 겹치는 전자음악 그림",
    prompt:
      "Electric violet and magenta electronic music banner. Concentric orbiting rings, a stylized planet and abstract synthesizer waveforms layered on the right side. Retro-futurist palette on deep space navy",
  },
  {
    slug: "paper-plane",
    concert: "종이비행기",
    desc: "종이비행기가 부드러운 언덕 위로 호를 그리는 그림",
    prompt:
      "Warm cream and terracotta acoustic concert banner. A single folded paper plane arcing over soft rolling hill shapes with a thin dotted flight path on the right side. Gentle sunlit palette, lots of quiet space",
  },
  {
    slug: "low-tide",
    concert: "LOW TIDE",
    desc: "낮은 해가 걸린 바다 수평선 띠 그림",
    prompt:
      "Muted teal and slate ambient music banner. Layered ocean horizon bands, a low pale sun and calm wet-sand reflections on the right side. Minimal misty coastal palette",
  },
  {
    slug: "night-radio",
    concert: "밤의 라디오",
    desc: "빈티지 방송 마이크에서 전파가 퍼지는 심야 그림",
    prompt:
      "Deep indigo and warm amber late-night radio banner. A vintage broadcast microphone with concentric radio waves rippling outward and a crescent moon on the right side. Cozy retro palette",
  },
  {
    slug: "crossfade-2026",
    concert: "CROSSFADE 2026",
    desc: "노을빛 하늘 아래 관객 실루엣이 겹치는 페스티벌 그림",
    prompt:
      "Saturated sunset orange and hot pink festival banner. Overlapping crowd silhouettes with raised hands, confetti bursts and stage truss shapes on the right side. Bold graphic palette",
  },
];

const force = process.argv.includes("--force");
const onlyIndex = process.argv.indexOf("--only");
const only = onlyIndex >= 0 ? process.argv[onlyIndex + 1] : null;

async function exists(p) {
  try {
    await access(p);
    return true;
  } catch {
    return false;
  }
}

/** .env.local에서 FAL_KEY만 꺼낸다. 값은 어디에도 찍지 않는다. */
async function readFalKey() {
  let raw;
  try {
    raw = await readFile(ENV_FILE, "utf8");
  } catch {
    throw new Error(`${ENV_FILE}을 읽지 못했습니다. FAL_KEY가 거기 있어야 합니다.`);
  }
  const line = raw.split("\n").find((l) => l.trim().startsWith("FAL_KEY="));
  const key = line?.slice(line.indexOf("=") + 1).trim().replace(/^["']|["']$/g, "");
  if (!key) throw new Error(`${ENV_FILE}에 FAL_KEY가 없습니다.`);
  return key;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function falJson(url, key, init = {}) {
  const res = await fetch(url, {
    ...init,
    headers: { Authorization: `Key ${key}`, "content-type": "application/json", ...init.headers },
  });
  const body = await res.text();
  if (!res.ok) {
    // 본문에 키가 섞여 나오지 않는다. 그래도 앞부분만 보여준다.
    throw new Error(`fal ${res.status}: ${body.slice(0, 200)}`);
  }
  return JSON.parse(body);
}

/**
 * 한 장 생성. 큐에 넣고 끝날 때까지 기다린다.
 *
 * `status_url`과 `response_url`은 **받은 그대로** 쓴다. 제출은 `fal-ai/flux/dev`로 하는데
 * 조회 주소는 `fal-ai/flux/requests/{id}`로 내려온다 — 모델 경로에서 변형이 빠진다.
 * 직접 조립하면 404가 난다(finmate-api FalArtProvider의 주석).
 */
async function generate(prompt, key) {
  let lastError;
  for (const size of GEN_SIZES) {
    try {
      const submitted = await falJson(`https://queue.fal.run/${MODEL}`, key, {
        method: "POST",
        body: JSON.stringify({ prompt, image_size: size, num_images: 1 }),
      });
      const { status_url: statusUrl, response_url: responseUrl } = submitted;
      if (!statusUrl || !responseUrl) throw new Error("작업 주소를 받지 못했습니다");

      for (let tries = 0; tries < 90; tries += 1) {
        await sleep(2000);
        const status = await falJson(statusUrl, key);
        if (status.status === "COMPLETED") {
          const result = await falJson(responseUrl, key);
          const image = result.images?.[0];
          if (!image?.url) throw new Error("완료됐는데 이미지 주소가 없습니다");
          const bytes = Buffer.from(await fetch(image.url).then((r) => r.arrayBuffer()));
          return { bytes, requested: size, returned: { width: image.width, height: image.height } };
        }
        if (status.status !== "IN_QUEUE" && status.status !== "IN_PROGRESS") {
          throw new Error(`작업이 ${status.status} 상태로 끝났습니다`);
        }
      }
      throw new Error("3분 안에 끝나지 않았습니다");
    } catch (error) {
      lastError = error;
      console.log(`  ${size.width}×${size.height} 실패 (${error.message}) — 더 작은 크기로 재시도`);
    }
  }
  throw lastError;
}

const key = await readFalKey();
await mkdir(OUT, { recursive: true });

const targets = BANNERS.filter((b) => !only || b.slug === only);
if (only && targets.length === 0) {
  throw new Error(`--only ${only}: 그런 배너가 없습니다. (${BANNERS.map((b) => b.slug).join(", ")})`);
}

for (const banner of targets) {
  const marker = path.join(OUT, `${banner.slug}-1440.avif`);
  if (!force && (await exists(marker))) {
    console.log(`· ${banner.slug} 이미 있음 (--force로 다시 생성 — 비용이 듭니다)`);
    continue;
  }

  process.stdout.write(`✎ ${banner.slug} 생성 중… `);
  const { bytes, requested, returned } = await generate(`${banner.prompt}. ${SUFFIX}`, key);
  // 요청한 크기를 그대로 믿지 않는다. 모델이 다른 크기를 돌려주기도 한다.
  const actual = await sharp(bytes).metadata();
  console.log(
    `${(bytes.length / 1024).toFixed(0)}KB · 요청 ${requested.width}×${requested.height}` +
      ` · 응답 ${returned.width ?? "?"}×${returned.height ?? "?"}` +
      ` · 실제 ${actual.width}×${actual.height} (비율 ${(actual.width / actual.height).toFixed(2)})`,
  );

  for (const w of WIDTHS) {
    // 3:1로 맞춘다. 모델이 정확히 3:1을 주지 않을 수 있어 여기서 한 번 더 못 박는다.
    // 배너는 오른쪽에 주제가 있으므로 가운데를 기준으로 자른다 — attention은 주제를
    // 가운데로 끌고 와 왼쪽 여백을 없앤다.
    const base = sharp(bytes).resize(w, Math.round(w / RATIO), { fit: "cover", position: "centre" });
    await writeFile(
      path.join(OUT, `${banner.slug}-${w}.avif`),
      await base.clone().avif({ quality: 52, effort: 6 }).toBuffer(),
    );
    await writeFile(
      path.join(OUT, `${banner.slug}-${w}.webp`),
      await base.clone().webp({ quality: 74 }).toBuffer(),
    );
  }
  console.log(`  → ${banner.slug}-{${WIDTHS.join(",")}}.{avif,webp}`);
}

await writeFile(
  path.join(OUT, "CREDITS.md"),
  [
    "# 공연 배너 그림 출처",
    "",
    "**이 여섯 장은 생성한 그림입니다.** fal.ai의 `fal-ai/flux/dev`로 만들었습니다.",
    "`web/scripts/generate-banners.mjs`에 프롬프트가 그대로 있습니다.",
    "",
    "## 포스터와 다릅니다",
    "",
    "`public/posters/`의 여섯 장은 **Pexels 실사 사진**입니다. 배너만 생성물입니다.",
    "",
    "실제 예매 서비스의 배너는 사진을 잘라 넣은 것이 아니라 **배너 비율로 그린 그림**입니다.",
    "NOL 티켓을 재 보면 배너는 1425×463(3.08:1)이고 글자까지 구워진 일러스트 한 장이었습니다",
    "— 활성 슬라이드의 텍스트 노드가 0개였습니다.",
    "",
    "3:4 세로 사진을 3:1로 자르면 그 흉내가 됩니다. 우리 사진 여섯 장 중 둘은 세로라",
    "잘라 봐야 얇은 띠만 남습니다. 그래서 배너는 배너 비율로 새로 그렸습니다.",
    "",
    "## 글자는 그림에 넣지 않았습니다",
    "",
    "공연명·공연장·날짜는 화면에서 **HTML 활자로 얹습니다**. 그래야 화면 낭독기가 읽고,",
    "어느 배율에서도 선명하고, 공연 정보가 바뀌어도 그림을 다시 만들지 않아도 됩니다.",
    "그림은 주제를 오른쪽에 두고 왼쪽을 비우도록 그렸습니다 — 그 자리가 활자 자리입니다.",
    "",
    "| 공연 | 그림 |",
    "| --- | --- |",
    ...BANNERS.map((b) => `| ${b.concert} | ${b.desc} |`),
    "",
    "## 생성",
    "",
    "`node scripts/generate-banners.mjs` — 3:1로 960/1440/1920 폭의 AVIF·WebP로 저장합니다.",
    "**비용이 발생하므로** 이미 있으면 건너뜁니다. 다시 만들려면 `--force`,",
    "프롬프트를 시험할 때는 `--only <slug>`로 한 장만 뽑습니다.",
    "",
  ].join("\n"),
);

console.log(`\n완료 → ${OUT}`);
