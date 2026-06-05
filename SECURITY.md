# PM chat Security Notes

## Client-side password hashing

PM chat supports a compatibility matrix for password authentication:

- Legacy users keep `BCRYPT_LEGACY` and can still log in with plaintext-over-TLS clients.
- New clients register with `CLIENT_ARGON2_BCRYPT`: the browser derives an Argon2id `clientHash`; the server stores only BCrypt(clientHash).
- Password change can upgrade a legacy account by proving the old legacy password and storing a new client-hashed credential.
- Accounts already on `CLIENT_ARGON2_BCRYPT` reject plaintext-only clients with `CLIENT_TOO_OLD`.
- Legacy accounts reject client-hash-only login attempts with `PASSWORD_UPGRADE_REQUIRED`.

The server endpoint `GET /api/auth/client-salt-params?username=...` returns existing client salt metadata when present. For users without client-hash credentials, including nonexistent users, it returns a deterministic HMAC-derived fake salt and the legacy scheme so the endpoint does not directly reveal whether a username exists. Responses are sent with `Cache-Control: no-store`.

## Threat model — what this defends and what it does NOT (CRITICAL)

### ✅ Defends against

- **服务器管理员读 plaintext (新 scheme 用户)**: 登录请求体里没有 plaintext,日志、heap dump、debugger 都拿不到。**Legacy 用户在迁移前仍会发 plaintext**,该缺口由 banner + 鼓励改密缩小,而不是消除。
- **DB dump / 数据库被脱 (新 scheme 用户)**: 表里存的是 `BCrypt(Argon2id(plaintext, salt))`。攻击者要先暴力 BCrypt (cost 12 ≈ 250ms/guess) 再暴力 Argon2id (m=64MB t=3 ≈ 500ms/guess) — 组合成本 ~750ms/guess + 64MB 内存。Legacy 用户在迁移前 DB 里仍是 BCrypt(plaintext),只比单 BCrypt 安全度。
- **传票 / 法律强制披露 (新 scheme 用户)**: 服务器物理上没有 plaintext,没法交。
- **凭据撞库 (credential stuffing 到别的服务)**: 新 scheme 用户从未把 plaintext 发给本服务器,撞库换站也没用。

### ⚠️ Does NOT defend against (诚实标注,必须写进 SECURITY.md)

- **TLS 中间人**: clientHash 本身就是 credential。如果攻击者拿到这一个 HTTPS 请求体,他不用反推 plaintext 就能直接重放到本服务器登录。**TLS 仍然是必需的安全前提**,不是可选。
- **被攻陷的前端 JS / 供应链投毒**: 如果攻击者能往 `auth_service.dart` 或 hash 库代码注入,他可以在 Argon2 算之前就把 plaintext 抓走。**缓解**: 钉死 `pubspec.lock` + CSP `script-src 'self'` + SRI on third-party CDN scripts (本项目目前没有第三方 CDN script,保持下去)。**这条威胁本批次解决不了**,只能降低面积。
- **服务器主动替换 JS 做 keylogger**: 服务器有 root 后可以把 nginx 直 serve 的 build/web 改了。CSP + 客户端二进制 hash 公示能让外部用户对比,**但用户多数不会去对比**。这是 web app 的本质风险。
- **登录中读 session memory**: 用户提交那一瞬间,服务器堆里仍然短暂持有 `request.body` (含 clientHash) 直到 GC。clientHash 在传输层 = credential,因此堆里这块内存的敏感性等价于 BCrypt hash 输入,不等于 plaintext。比今天好,不是零。
- **离线字典攻击弱口令**: m=64MB t=3 让单次 Argon2id 成本 ≈ 500ms,但弱口令 (Top 10000) 仍能在数天内跑完。**用户教育 (强制最小长度 ≥ 8,推荐 passphrase) 仍是必要的补充**。
- **HMAC key 失效**: `CLIENT_SALT_HMAC_SECRET` 一旦泄漏,攻击者能为非存在用户预算 fake salt,从而通过 fake_salt vs real_salt 的差异判断用户存在性。建议每 12 个月轮换(轮换会让 legacy/non-existent 用户的 Argon2 重算一次,无功能影响)。
- **Legacy 用户迁移前的窗口期**: 仍然 plaintext。banner 鼓励但不强制迁移;30 天后看迁移率,如 < 70% 再做强制方案。

## Secret handling

`CLIENT_SALT_HMAC_SECRET` is required in the production profile and must be a high-entropy secret managed outside git. The development profile has a fallback solely so local tests and dev startup remain possible. Do not print this secret in logs, scripts, screenshots, or test artifacts.

DTO `toString()` methods exclude plaintext passwords, client hashes, and salts. Validation errors must not log raw secrets.

## Rate limits and lockout

- `/api/auth/*` requests are IP limited by the existing auth bucket.
- `client-salt-params` also has a per-username 30/hour bucket.
- Failed login attempts are counted per normalized username; 50 failures within 24 hours lock login until the window expires.

## Reset and recovery audit

Password reset / recovery endpoints were audited during this batch and no active main-code reset flow was found. If one is added later, it must use the same client-hash credential bundle on password set.
