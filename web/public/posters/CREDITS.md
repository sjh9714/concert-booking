# 공연 포스터 사진 출처

여섯 장 모두 [Pexels](https://www.pexels.com/license/)에서 받은 **실제 촬영 사진**입니다.
AI로 생성한 이미지가 아닙니다.

Pexels 라이선스는 상업적 사용과 수정을 허용하며 출처 표기를 요구하지 않지만,
확인 가능하도록 남겨 둡니다.

| 공연 | 장면 | 원본 |
| --- | --- | --- |
| NOCTURNE — SEOUL | 푸른 조명이 부챗살처럼 퍼지는 야간 공연장 | [Pexels #2263435](https://www.pexels.com/photo/2263435/) |
| ORBITAL WEEKEND | 헤드폰을 쓰고 장비를 다루는 디제이 | [Pexels #15235342](https://www.pexels.com/photo/15235342/) |
| 종이비행기 | 무대에서 노래하는 사람과 그 앞의 관객 | [Pexels #878998](https://www.pexels.com/photo/878998/) |
| LOW TIDE | 어두운 무대 위로 떨어지는 조명과 연주자들 | [Pexels #15583331](https://www.pexels.com/photo/15583331/) |
| 밤의 라디오 | 방송용 마이크가 놓인 스튜디오 | [Pexels #272795](https://www.pexels.com/photo/272795/) |
| CROSSFADE 2026 | 무대를 가득 메운 페스티벌 관객 | [Pexels #248963](https://www.pexels.com/photo/248963/) |

## 이 사진들은 실제 공연 포스터가 아닙니다

데모용 가상 공연이므로 진짜 포스터가 존재하지 않습니다. 분위기가 맞는 사진을 골라
포스터 자리에 쓰고, 공연명과 아티스트는 **이미지에 굽지 않고 화면에서 활자로 얹습니다.**
그래야 화면 낭독기가 읽을 수 있고 어느 배율에서도 선명합니다.

## 생성

`node scripts/fetch-posters.mjs` — 3:4로 잘라 240/480/720 폭의 AVIF·WebP로 저장합니다.
비율 3:4는 NOL 티켓(0.753)과 예스24(0.71)를 실측해 정한 값입니다(`web/DESIGN.md`).
