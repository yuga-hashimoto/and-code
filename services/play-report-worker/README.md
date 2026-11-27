# Optional private AI report receiver

This minimal service supports the Play app's optional accountless reporting path. It is **not deployed**
and the app defaults to the separately labeled public GitHub flow until an operator configures it.
It accepts only a reason and a user-entered excerpt, not an automatically attached transcript, files,
authentication token, or account identifier. Input validation cannot reliably identify all secrets or
personal data. Cloudflare processes connection metadata including IP addresses; the Worker uses an IP
for approximate per-colocation rate limiting and does not store it in the report table. Reports are
deleted from the table after 90 days by a daily scheduled task; provider logs and backups are governed
by Cloudflare's terms and may have different retention. Abuse controls are approximate, not a guarantee.

Run `npm ci`, `npm test`, `npm run check:deploy`, and `npm run test:integration`. For manual local integration, use `wrangler d1 migrations
apply andcode-ai-reports --local --config wrangler.example.jsonc` and `wrangler dev --local --config
wrangler.example.jsonc`. The example contains placeholders and must not be deployed unchanged.

Before operating this service, an operator must choose and secure an account, a D1 database and a
unique rate-limit namespace, apply migrations, deploy the Worker with a daily cron, establish a private
moderation workflow and deletion/abuse response, and review actual provider retention and costs. Protect
access to D1, backups and logs; do not publish reports. A bare write-only endpoint without someone
monitoring the database is not a complete moderation process. Configure the public, HTTPS endpoint at
build time with `AND_CODE_PLAY_REPORT_ENDPOINT=https://<host>/v1/reports`; it must have no credentials,
query, or fragment. The private path does not fall back to a public issue when submission fails.
Deployment, service setup and Play submission are outside this change.
