# TIMOA Chat Server

> **REST + SSE 기반 팀 채널 채팅 서버**  
> 메시지 전송은 REST, 실시간 수신은 SSE로 분리하고, 첨부파일은 AWS S3에 저장합니다.

![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square)
![MSSQL](https://img.shields.io/badge/MS_SQL-CC2927?style=flat-square&logo=microsoftsqlserver&logoColor=white)
![AWS S3](https://img.shields.io/badge/AWS_S3-569A31?style=flat-square&logo=amazons3&logoColor=white)

---

## 개요

TIMOA의 **Discord 텍스트 채널** 대체 기능을 담당합니다.

초기에는 STOMP(WebSocket)로 구현했지만, 채팅은 "보내기는 가끔, 받기는 계속"이라는 비대칭 패턴이라 양방향 연결을 항상 유지할 필요가 없었습니다.  
그래서 **전송은 REST, 수신은 SSE(Server-Sent Events)** 로 분리해 연결 관리를 단순화하고, HTTP 인프라(Proxy · 인증 · 로깅)를 그대로 활용하도록 했습니다.

→ 결정 배경: [ADR 0009 — Chat REST + SSE](https://github.com/TIMOA-MAIN/TIMOA_Docs/blob/main/protocol/0009-chat-rest-sse.md) · [ADR 0008 — STOMP 제거](https://github.com/TIMOA-MAIN/TIMOA_Docs/blob/main/decisions/service/0008-stomp-removal.md) · [ADR 0013 — 파일 S3 이전](https://github.com/TIMOA-MAIN/TIMOA_Docs/blob/main/data/0013-file-s3-migration.md)

```
Client ── POST /api/chats/send ──▶ Chat Server ──▶ MS SQL (메시지 저장)
                                       │
                                       ├──▶ SSE broadcast → 채널 멤버 전원
                                       └──▶ 멘션 대상 → 개별 알림

Client ── POST /api/chats/upload ─▶ Chat Server ──▶ AWS S3 (첨부파일)
Client ◀═ GET /sse/user/{userId} ══ (5분 타임아웃, keep-alive 주기 전송)
```

## 주요 기능

### 메시지
- 메시지 전송 · 수정 · 삭제, 수정/삭제 이벤트도 SSE로 즉시 반영
- **커서 기반 페이지네이션**: 최근 N개 로드 후, 스크롤 시 `before` 시각 기준으로 이전 메시지 조회 (`has_more` 반환)
- **@멘션 알림**: 메시지에 포함된 서버 멤버에게 개별 SSE 알림 전송

### 채널
- 팀 서버별 텍스트 채널 생성 · 이름 변경 · 삭제
- 사용자가 속한 서버의 채널 목록 조회

### 실시간 전송 (SSE)
- 사용자별 `SseEmitter` 목록을 `ConcurrentHashMap<userId, CopyOnWriteArrayList>`로 관리 → 다중 창/기기 동시 수신
- 재연결 시 기존 Emitter를 정리해 유령 연결 방지
- 스케줄러로 주기적 **keep-alive** 전송, 실패한 Emitter 자동 제거
- 브로드캐스트 범위: 서버 전체 / 채널 멤버 / 개별 사용자

### 파일 첨부
- 최대 50MB 업로드, UUID 기반 파일명으로 S3 저장
- 다운로드는 서버를 거쳐 스트리밍 (버킷 비공개 유지)
- DB에는 메타데이터만 저장해 용량 절감 → [ADR 0014](https://github.com/TIMOA-MAIN/TIMOA_Docs/blob/main/data/0014-s3-unstructured-data.md)

## API

### 메시지 `/api/chats`
| Method | Path | 설명 |
|---|---|---|
| `POST` | `/send` | 메시지 전송 |
| `POST` | `/update` | 메시지 수정 |
| `DELETE` | `/delete` | 메시지 삭제 |
| `POST` | `/upload` | 첨부파일 업로드 (S3) |
| `GET` | `/download` | 첨부파일 다운로드 |
| `GET` | `/channels/{serverId}/{channelId}/messages` | 최근 메시지 |
| `GET` | `/channels/{serverId}/{channelId}/messages/before` | 이전 메시지 (페이지네이션) |

### 채널 `/api/chat/channels`
| Method | Path | 설명 |
|---|---|---|
| `POST` | `/create/{userId}` | 채널 생성 |
| `PUT` | `/update/{channelId}` | 채널 수정 |
| `DELETE` | `/delete/{channelId}` | 채널 삭제 |
| `GET` | `/server/{serverId}/user/{userId}` | 채널 목록 |

### SSE / 기타
| Method | Path | 설명 |
|---|---|---|
| `GET` | `/sse/user/{userId}` | 실시간 이벤트 구독 (`text/event-stream`) |
| `GET` | `/sse/status` | SSE 연결 현황 |
| `GET` | `/health` | 헬스체크 |

## 프로젝트 구조

```
src/main/java/TIMOA/chatserver/
├── controller/   # Chat, Channels, Sse, ping(health)
├── service/      # Chat_service, Chat_channel_service, Sse_service, Server_member_service
├── repository/   # JPA Repository + S3FileRepository
├── entity/       # Chat_messages, Chat_channels, Servers, Server_members, Users
├── DTO/          # 요청/응답 DTO
└── config/       # WebConfig(CORS), GlobalExceptionHandler
```

## 실행

```bash
./gradlew bootRun
```

### 설정 (`application.properties` / 환경 변수)
| 키 | 설명 |
|---|---|
| `spring.datasource.url` / `username` / `password` | MS SQL 접속 정보 |
| `jwt.secret` | JWT 서명 키 |
| `spring.cloud.aws.credentials.*` | AWS 자격 증명 (환경 변수로 주입) |
| `spring.cloud.aws.region.static` | S3 리전 |
| `server.port` | 기본 `8002` |

## 관련 레포
- [Desktop_Client](https://github.com/TIMOA-MAIN/Desktop_Client) — 채팅 UI
- [Proxy_Server](https://github.com/TIMOA-MAIN/Proxy_Server) — `/proxy/chat`, `/proxy/sse-chat`, `/chat-upload` 라우팅
