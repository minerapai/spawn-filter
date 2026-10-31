# Spawn Filter (스폰 필터)

설정한 지방(관동, 성도 ...)의 포켓몬만 자연 스폰되게 하는 모드입니다. 진화와 교배에는 영향을 주지 않습니다.

- 버전: 1.10.2
- Minecraft 1.21.1 / Fabric
- 필요한 모드: Cobblemon, Fabric API
- 설치: 서버와 클라이언트 모두에 넣어야 화면(UI)을 쓸 수 있습니다. 모드가 없는 사람도 서버에 접속은 할 수 있습니다.

## 다운로드

**[⬇ spawn-filter-1.10.2.jar 다운로드](https://github.com/minerapai/spawn-filter/releases/download/v1.10.2/spawn-filter-1.10.2.jar)**

받은 jar 파일을 마인크래프트(또는 서버) 폴더의 `mods` 폴더에 넣으면 됩니다. 다른 버전은 [Releases](https://github.com/minerapai/spawn-filter/releases) 에 있습니다.

## 처음 설치하면

처음 설치하면 **모든 지방이 허용**되어 있어서 스폰이 전혀 바뀌지 않습니다. 지방을 제한하려면 `config/spawn-filter.properties` 의 `regions` 를 원하는 지방으로 바꾸고 `/spawnfilter apply` 를 실행하세요. (예: `regions=kanto` → 관동 포켓몬만 스폰)

[Poke Events](https://github.com/minerapai/poke-events) 의 대량발생을 쓰려고 이 모드를 넣는 경우에도 그대로 두면 됩니다.

## 기능

- 지방별 스폰 허용/차단
- 구역(area)마다 다른 지방 / 포켓몬 배율 / 강제 스폰 설정
- 경계 밖에 나올 지방 설정
- 스폰 확률 설정 화면 (클라이언트에도 모드가 있어야 열림)

## 사용법

모든 명령어는 서버 관리자(OP)만 쓸 수 있습니다.

| 명령어 | 설명 |
|---|---|
| `/spawnfilter` | 지금 설정 보기 |
| `/spawnfilter ui` | 지방 선택 화면 (클라이언트 모드 없어도 됨) |
| `/spawnfilter rates` | 구역 / 스폰 확률 화면 (클라이언트 모드 필요) |
| `/spawnfilter regions <지방들>` | 허용할 지방 (예: `kanto,johto`) |
| `/spawnfilter area <x1> <z1> <x2> <z2>` | 맵 경계 (`area clear`로 없애기) |
| `/spawnfilter outside <지방들>` | 경계 바깥에 나올 지방 (`all` 가능) |
| `/spawnfilter allow <포켓몬들>` | 항상 나오게 할 포켓몬 |
| `/spawnfilter deny <포켓몬들>` | 절대 안 나오게 할 포켓몬 |
| `/spawnfilter enabled <true/false>` | 지방 필터 켜기 / 끄기 |
| `/spawnfilter removeExisting <true/false>` | 이미 있는 허용되지 않은 포켓몬 정리 여부 |
| `/spawnfilter cleanup` | 로드된 허용되지 않은 야생 포켓몬 바로 정리 |
| `/spawnfilter apply` | 바꾼 지방 설정 적용 (스폰 목록 다시 읽기) |

단축키 (클라이언트에 모드가 있을 때): `[` 지방 선택 화면, `]` 구역 / 스폰 확률 화면, `\` 구역 표시 켜기 / 끄기


## 빌드

```bash
./gradlew build
```

`build/libs/` 에 만들어진 jar 파일을 서버의 `mods` 폴더에 넣습니다.

## 라이선스

MIT License. 출처(minerapai)만 밝히면 누구나 자유롭게 쓰고 고치고 배포할 수 있습니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 보세요.
