# Production Readiness

## Required Environment

- `SPRING_PROFILES_ACTIVE=prod`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` when Redis auth is enabled
- `JWT_SECRET`: base64 random secret, at least 64 characters
- `CORS_ALLOWED_ORIGINS`: explicit comma-separated origins, no wildcard
- `WS_ALLOWED_ORIGINS`: explicit WebSocket origins
- `FILE_UPLOAD_PATH`: writable absolute path with backup policy
- At least one of `OPENAI_API_KEY`, `CLAUDE_API_KEY`, `DEEPSEEK_API_KEY`,
  `OLLAMA_BASE_URL`, or `AGENT_GATEWAY_ENABLED=true` + `AGENT_GATEWAY_BASE_URL`
  when `LLM_REQUIRED=true`
- Optional external Agent Gateway:
  - `AGENT_GATEWAY_PROVIDER`: `openclaw`, `hermes`, or `generic`
  - `AGENT_GATEWAY_BASE_URL`
  - `AGENT_GATEWAY_EXECUTE_PATH`
  - `AGENT_GATEWAY_HEALTH_PATH`
  - `AGENT_GATEWAY_API_KEY`

## Health Checks

- Public liveness: `GET /actuator/health`
- Component readiness: actuator includes `llm` and `agentGateway` health indicators
- Application API smoke: authenticated `GET /api/v1/chat-rooms`
- WebSocket smoke: connect to `/api/ws?token=<accessToken>` and send `{"type":"ping"}`
- File ACL smoke: non-member cannot read `/api/files/chat/<fileName>`

## Database

- Flyway migrations are required in production.
- New phase 11-16 tables are in `V3__agent_tasks_and_audit_logs.sql`.
- Production profile keeps `spring.jpa.hibernate.ddl-auto=validate`.

## Release Gate

Run:

```bash
./scripts/verify-all.sh
RUN_WEB_BUILD=1 API_BASE_URL=https://chat.example.com WS_BASE_URL=wss://chat.example.com ./scripts/verify-all.sh
```

The gate runs whitespace checks, backend tests, and frontend tests. With
`RUN_WEB_BUILD=1`, it also builds the production web bundle using the supplied
API/WebSocket origins.

After deploying to staging, run:

```bash
BASE_URL=https://chat.example.com WS_URL=wss://chat.example.com \
  python3 /data2/chat_project/e2e_smoke_test.py
```

The smoke test covers health, registration/login, group chat, WebSocket
broadcast, file ACL, and anonymous message metadata.
