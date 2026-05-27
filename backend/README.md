# Tango Tori Backend

Cloudflare Worker that provides compound Chinese word meanings via Claude AI,
with a shared D1 cache so each unique compound is only ever looked up once.

## First-time setup

### 1. Install dependencies

```bash
cd backend
npm install
```

### 2. Create the D1 database

```bash
npx wrangler d1 create tango-tori-compounds
```

Copy the `database_id` from the output into `wrangler.toml`.

### 3. Apply the schema

```bash
npx wrangler d1 execute tango-tori-compounds --file=schema.sql
```

### 4. Set your Anthropic API key

```bash
npx wrangler secret put ANTHROPIC_API_KEY
# paste your key when prompted
```

### 5. Deploy

```bash
npm run deploy
```

Copy the `*.workers.dev` URL from the output into your Android `local.properties`:

```properties
COMPOUND_API_URL=https://tango-tori-backend.<your-subdomain>.workers.dev
```

## Local development

```bash
npm run dev
```

The Worker runs at `http://localhost:8787`. Point `COMPOUND_API_URL` there while testing.

## Endpoint

```
POST /compound
Content-Type: application/json

{
  "device_id": "<uuid>",
  "compound": "今天天气",
  "parts": [
    { "text": "今天", "pinyin": "jīn tiān", "meaning": "today" },
    { "text": "天气", "pinyin": "tiān qì",  "meaning": "weather" }
  ]
}

→ { "meaning": "today's weather", "cached": false }
```

Rate limit: 100 compound lookups per device per day (configurable via `RATE_LIMIT_PER_DAY` in `wrangler.toml`).
