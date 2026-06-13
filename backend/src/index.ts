import Anthropic from "@anthropic-ai/sdk";

export interface Env {
  DB: D1Database;
  ANTHROPIC_API_KEY: string;
  /** Per-device daily token-cost budget, in micro-USD (millionths of a dollar).
   *  Default 10000 = $0.01 = 1 cent/day. */
  DAILY_BUDGET_MICRO_USD?: string;
  /** Comma-separated device_ids exempt from the daily budget (e.g. dev phone). */
  BYPASS_DEVICE_IDS?: string;
  /** Google Play purchase verification (both secrets, set via `wrangler secret
   *  put`): the service account's email and its PKCS8 private key PEM (the
   *  `client_email` / `private_key` fields of the service-account JSON).
   *  When configured, usage tiers come ONLY from server-verified purchases
   *  (D1 `device_tier`); when absent, the worker falls back to trusting the
   *  client-claimed `budget_tier`. */
  GOOGLE_SA_EMAIL?: string;
  GOOGLE_SA_PRIVATE_KEY?: string;
}

/** Play package + product catalog (public knowledge — safe to commit). */
const PLAY_PACKAGE_NAME = "com.tangotori.app";
const PRODUCT_TIERS: Record<string, number> = {
  usage_boost_8x: 8,
  usage_boost_20x: 20,
  usage_boost_upgrade_20x: 20,
};

interface Part {
  text: string;
  pinyin: string;
  meaning: string;
}

interface CompoundRequest {
  device_id: string;
  compound: string;
  parts: Part[];
  /** Sent by the app. false = a bypass-listed (dev) device opted out of its
   *  bypass via the in-app Dev Mode toggle → meter it like a free user.
   *  Absent/true = normal behavior. Ignored for non-bypassed devices. */
  dev_mode?: boolean;
  /** Bring-your-own-key: the user's own Anthropic API key. When present the
   *  LLM call is billed to it and the daily budget doesn't apply. NEVER
   *  stored or logged — used for this one upstream call only. */
  api_key?: string;
  /** Purchased usage tier (1 = free, 8 or 20 = paid boost). Multiplies the
   *  daily budget. Client-claimed (no Play verification yet). */
  budget_tier?: number;
}

interface CompoundResponse {
  meanings: string[];
  cached: boolean;
}

interface Candidate {
  id: number;
  reading: string;
  pos: string;
  gloss: string;
}

interface DisambiguateRequest {
  device_id: string;
  language: string; // "ja" | "zh"
  word: string;
  sentence: string;
  candidates: Candidate[];
  /** See CompoundRequest.dev_mode. */
  dev_mode?: boolean;
  /** See CompoundRequest.api_key. */
  api_key?: string;
  /** See CompoundRequest.budget_tier. */
  budget_tier?: number;
}

/** Clamp the client-claimed purchase tier to the known products. */
function sanitizeTier(tier: unknown): number {
  return tier === 8 || tier === 20 ? tier : 1;
}

interface ExpressionPart {
  word: string;
  reading: string;
  /** Glosses of the part's displayed dictionary entry, one string per sense in
   *  order. The model returns the 1-based index of the best-matching sense. */
  senses?: string[];
}

interface ExpressionPartsRequest {
  device_id: string;
  expression: string;
  parts: ExpressionPart[];
  dev_mode?: boolean;
  api_key?: string;
  budget_tier?: number;
}

interface ExpressionPartGloss {
  /** Meaning of the part as it functions inside the expression. */
  meaning: string;
  /** 1-based index of the matched candidate sense, or 0 if none fit. */
  sense: number;
}

interface ExpressionPartsResponse {
  /** One result per part, parallel to the request `parts`. */
  parts: ExpressionPartGloss[];
  cached: boolean;
}

// ── Google Play purchase verification ───────────────────────────────────────

function googleConfigured(env: Env): boolean {
  return !!(env.GOOGLE_SA_EMAIL && env.GOOGLE_SA_PRIVATE_KEY);
}

/**
 * Resolves the tier to use for budget math. With Play verification configured,
 * only the server-verified tier (D1 `device_tier`, written by /verify_purchase)
 * counts — the client claim is ignored. Without it, fall back to the claim
 * (bounded abuse: ≤ 20x of a 1-cent budget).
 */
async function effectiveTier(
  env: Env,
  deviceId: string,
  claimed: unknown
): Promise<number> {
  if (!googleConfigured(env)) return sanitizeTier(claimed);
  const row = await env.DB.prepare(
    "SELECT tier FROM device_tier WHERE device_id = ?"
  )
    .bind(deviceId)
    .first<{ tier: number }>();
  return sanitizeTier(row?.tier);
}

function b64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlString(s: string): string {
  return b64url(new TextEncoder().encode(s));
}

/** Parses a PKCS8 PEM (handles both real newlines and literal "\n"). */
async function importServiceAccountKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

// Access token cached per worker isolate (tokens live 1h; isolates are
// recycled far more often than that, so this is best-effort warm-keeping).
let googleToken: { token: string; expiresAt: number } | null = null;

/** Mints (or reuses) a Google OAuth2 access token via the service-account
 *  JWT-bearer flow, scoped to the Android Publisher API. */
async function googleAccessToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (googleToken && googleToken.expiresAt - 60 > now) return googleToken.token;

  const unsigned =
    b64urlString(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64urlString(
      JSON.stringify({
        // trim: secrets piped in via PowerShell can carry a stray \r\n
        iss: env.GOOGLE_SA_EMAIL!.trim(),
        scope: "https://www.googleapis.com/auth/androidpublisher",
        aud: "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      })
    );
  const key = await importServiceAccountKey(env.GOOGLE_SA_PRIVATE_KEY!);
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned)
  );
  const jwt = `${unsigned}.${b64url(new Uint8Array(sig))}`;

  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body:
      "grant_type=" +
      encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") +
      "&assertion=" +
      jwt,
  });
  if (!resp.ok) {
    // Body is e.g. {"error":"invalid_grant","error_description":"..."} —
    // log it (no secrets in there) so auth misconfig is diagnosable via tail.
    const detail = await resp.text().catch(() => "");
    throw new Error(`Google token exchange failed: ${resp.status} ${detail}`);
  }
  const data = (await resp.json()) as { access_token: string; expires_in: number };
  googleToken = { token: data.access_token, expiresAt: now + data.expires_in };
  return data.access_token;
}

/** Checks one Play purchase token with the Android Publisher API.
 *  Returns the tier it grants, or 0 if not a valid, purchased entitlement. */
async function verifyPlayPurchase(
  env: Env,
  productId: string,
  purchaseToken: string
): Promise<number> {
  const tier = PRODUCT_TIERS[productId];
  if (!tier) return 0;
  const token = await googleAccessToken(env);
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
    `${PLAY_PACKAGE_NAME}/purchases/products/${encodeURIComponent(productId)}` +
    `/tokens/${encodeURIComponent(purchaseToken)}`;
  const resp = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!resp.ok) return 0; // unknown/voided token, or wrong product
  const data = (await resp.json()) as { purchaseState?: number };
  // 0 = purchased; 1 = canceled; 2 = pending.
  return data.purchaseState === 0 ? tier : 0;
}

interface VerifyPurchaseRequest {
  device_id: string;
  /** The device's complete current set of owned boost purchases. Sent in full
   *  each time so the verified tier is set absolutely — an empty list (e.g.
   *  after a refund) legitimately downgrades the device to tier 1. */
  purchases: { product_id: string; purchase_token: string }[];
}

async function handleVerifyPurchase(request: Request, env: Env): Promise<Response> {
  if (!googleConfigured(env)) {
    return json({ error: "Purchase verification not configured" }, 501);
  }
  let body: VerifyPurchaseRequest;
  try {
    body = (await request.json()) as VerifyPurchaseRequest;
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }
  const { device_id, purchases } = body;
  if (!device_id || !Array.isArray(purchases) || purchases.length > 10) {
    return json({ error: "Missing/invalid fields: device_id, purchases" }, 400);
  }

  let tier = 1;
  for (const p of purchases) {
    if (typeof p?.product_id !== "string" || typeof p?.purchase_token !== "string") continue;
    try {
      tier = Math.max(tier, await verifyPlayPurchase(env, p.product_id, p.purchase_token));
    } catch (err) {
      // Google API hiccup — don't downgrade on transient failures.
      console.error("verify_purchase failed:", err instanceof Error ? err.message : err);
      return json({ error: "Verification temporarily unavailable" }, 503);
    }
  }

  await env.DB.prepare(
    `INSERT INTO device_tier (device_id, tier, product_ids, verified_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET
       tier = excluded.tier,
       product_ids = excluded.product_ids,
       verified_at = excluded.verified_at`
  )
    .bind(
      device_id,
      tier,
      purchases.map((p) => p.product_id).join(","),
      Date.now()
    )
    .run();

  return json({ tier });
}

interface DisambiguateResponse {
  id: number;
  meaning: string;
  cached: boolean;
}

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

// The system prompt is ~90% of each call's input cost, so it is kept as tight as
// possible while preserving every behavioral rule (never refuse, no meta-commentary,
// strict numbered-list format) and the load-bearing worked examples.
const SYSTEM_PROMPT = `You are a Chinese dictionary assistant. A Chinese word was not found in the dictionary and was split into parts by a tokenizer. Give a concise English meaning for the full compound.

## Output rules — strict

Your entire reply is ONLY a numbered list of 1 to 3 English glosses. Nothing else, ever.

**Forbidden in any reply:** explanations, reasoning, commentary, or notes; meta-statements ("This appears to be...", "Treating it as...", "The parts suggest..."); refusals or any mention that the input is unfamiliar, classical, fragmentary, or unmatched; hedging words ("possibly", "perhaps", "uncertain", "unclear"); pinyin, the original Chinese, parenthetical alternatives, headers, or labels; trailing punctuation on entries.

**Required in every reply:** one to three lines, each beginning \`1.\`, \`2.\`, or \`3.\`; each line one short English phrase; most natural / most common gloss first; a single entry when one phrase carries the full meaning, multiple only when English needs different wordings for distinct senses. **Every input gets glossed** — modern, classical, literary, technical, fragmentary, ungrammatical, or unfamiliar. Never refuse, never explain why you can't; the only fallback is to gloss it anyway.

## How to gloss

Read the parts (including less common, classical, or literary senses where relevant), identify the structure, and let the structure shape the gloss. When a productive grammatical pattern is present, its contribution — softening, intensifying, direction, completion, ability, frame meaning — takes priority over the bare character meanings.

- Modifier + head: 天气 sky-air → weather
- Verb + object: 吃饭 → eat a meal
- Verb + complement — resultative V完/V好/V到/V见/V住/V掉/V着 (喝完 → finish drinking); directional V上/下/进/出/回/过/起/开 with optional 来/去 (站起来 → stand up); potential V得X / V不X (看得见 → can see)
- Coordinate / parallel: 山水 → landscape; scenery
- Verb reduplication AA / A了A / A一A / AABB / AA+object — softens to brief, casual, tentative: 看看 → have a look; 想了想 → thought for a moment; 研究研究 → look into it a bit
- Adjective reduplication AA / AABB / ABB — vivid intensification, often adverbial: 慢慢 → slowly; 高高兴兴 → cheerfully; 红彤彤 → glowing red
- Aspectual softeners — V一下 (briefly: 试一下 → give it a try), V过 (experiential: 去过 → have been there)
- Fixed frames — 不X不Y neither X nor Y / just right; 又X又Y both X and Y; 越X越Y the more X the more Y; X来X去 back and forth; 一X一Y alternating opposites
- Intensifier suffixes — X得很 / X极了 / X死了 / X透了 → very / extremely X (累死了 → exhausted)
- Separable verbs split by an inserted element — reassemble the core meaning and reflect the inserted info: 帮了忙 → helped; 见过面 → have met before
- Productive suffixes — X化 -ization, X性 -ness, X者 -er, X家 expert, X员 member/staff, X师 professional (现代化 → modernization)
- Classical / literary phrasing (often with 此 彼 之 乎 矣 焉 兮 也) — compose the parts; a slightly archaic, compressed gloss is correct and natural, do not flag it
- Proper nouns / transliterations — the English name if recognizable, else a brief descriptive gloss
- Technical terms — compose the technical phrase (光合作用 → photosynthesis)
- Two adjacent words the tokenizer grouped — gloss as a natural English phrase joining their senses

## Worked examples

Compound: 天气
Parts:
• 天 (tiān): sky, heaven
• 气 (qì): air, gas, steam

→
1. weather

Compound: 看看书
Parts:
• 看 (kàn): look, see, read
• 看 (kàn): look, see, read
• 书 (shū): book

→
1. do some reading
2. read a bit
3. have a little read

Compound: 故彼生
Parts:
• 故 (gù): therefore, thus, because of this
• 彼 (bǐ): that, the other
• 生 (shēng): arise, be born, come into being

→
1. therefore that arises
2. thus that comes to be
3. so that is born`;

// Bump when the disambiguation prompt changes so cached results regenerate.
const DISAMBIGUATE_PROMPT_VERSION = "2";

// Kept deliberately separate from SYSTEM_PROMPT (compound glossing) so the two
// Haiku calls never share context — each gets a clean, single-purpose prompt.
const DISAMBIGUATE_SYSTEM_PROMPT = `You are a reading assistant for Japanese and Chinese learners. You receive a sentence, a target word AS IT APPEARS in that sentence (it may be conjugated or inflected), and a numbered list of candidate dictionary meanings for the word's BASE form. The candidates may come from several dictionary entries (homographs with different readings).

Your job has two parts:
1. Pick the ONE candidate number whose BASE meaning best matches how the word is used in the sentence.
2. Write a concise English meaning of the word AS IT IS ACTUALLY USED in this sentence — reflecting its conjugation and the surrounding grammar: tense (past/present), polarity (negative), politeness, and especially mood/modality: questions, invitations/suggestions, volitional ("let's"), potential ("can/be able to"), passive, causative, imperative, requests ("please…", "may I…"), progressive/continuous, and aspect (completed, experiential, ongoing).

The "id" is about the base dictionary sense; the "meaning" is about the live, conjugated usage, so they often differ. Example: base sense is "to go", but the sentence uses a negative-question invitation (行かない？), so the meaning is "shall we go?; do you want to go?".

## Output rules — strict
- Reply with ONLY a single JSON object: {"id": <number>, "meaning": "<contextual gloss>"}
- No other text, no markdown, no code fences, no commentary.
- "id" MUST be one of the candidate numbers shown.
- "meaning" is short — one or two phrasings separated by "; ". It must reflect the conjugation/grammar, not the bare dictionary form. Use a trailing "?" when it's a question or invitation; otherwise no trailing punctuation.
- Always choose. Never refuse, never hedge.

## Examples

Language: Japanese
Sentence: 一緒に行かない？
Word: 行かない
Candidate meanings:
1. (いく, v5k-s) to go; to move (towards); to head (towards)
2. (いく, v5k-s) to proceed; to turn out
3. (ゆく, v5k-s) to die; to pass away
→
{"id": 1, "meaning": "shall we go?; do you want to go?"}

Language: Japanese
Sentence: 昨日寿司を食べた。
Word: 食べた
Candidate meanings:
1. (たべる, v1) to eat
2. (たべる, v1) to live on; to make a living
→
{"id": 1, "meaning": "ate"}

Language: Japanese
Sentence: ここで写真を撮ってもいいですか。
Word: 撮って
Candidate meanings:
1. (とる, v5r) to take (a photo); to record
→
{"id": 1, "meaning": "may I take (a photo)?"}

Language: Chinese
Sentence: 我已经吃过饭了。
Word: 吃过
Candidate meanings:
1. (chī, v) to eat
2. (chī, v) to suffer; to incur a loss
→
{"id": 1, "meaning": "have eaten (already)"}

Language: Chinese
Sentence: 我们走吧。
Word: 走
Candidate meanings:
1. (zǒu, v) to walk; to go
2. (zǒu, v) to leave; to depart
→
{"id": 1, "meaning": "let's go; let's get going"}`;

// Bump when the expression-parts prompt/response shape changes so cached
// results regenerate. (v2: added per-part matched-sense selection.)
const EXPRESSION_PARTS_PROMPT_VERSION = "2";

// In-expression meaning of each component of a Japanese idiom / compound, plus
// which of the component's dictionary senses best matches that in-expression
// use (so the app can highlight it in red, like /disambiguate). Kept separate
// (single-purpose, own D1 cache). Cached per EXPRESSION (sentence-independent).
const EXPRESSION_PARTS_SYSTEM_PROMPT = `You are a Japanese language assistant. You receive a Japanese expression (an idiom, set phrase, or compound word) and its component words in order. Each component lists its candidate dictionary senses, numbered.

For EACH component, return TWO things:
1. "meaning": a concise English gloss of that component AS IT FUNCTIONS WITHIN THIS EXPRESSION — its contribution, which can differ from the literal senses. Particles → grammatical role. Bound suffixes/prefixes → tilde form, e.g. "~student; person of ~".
2. "sense": the 1-based number of the candidate sense that best matches this in-expression use, or 0 if none of the listed senses fit (e.g. a bound suffix whose role isn't among them).

## Output rules — strict
- Reply with ONLY a JSON array of objects, one per component in order: [{"meaning": "...", "sense": N}, ...]
- The array length MUST equal the number of components. No other text, no markdown, no code fences.
- Keep each "meaning" short — a few words; separate alternates with "; "; no trailing punctuation.
- "sense" is an integer (the candidate number, or 0). Always return one object per component. Never refuse.

## Examples

Expression: 腹が立つ
Components:
1. 腹 (はら) — senses: 1. abdomen; belly 2. one's mind; true intentions 3. courage; nerve
2. が (が) — senses: 1. indicates the subject 2. but; however
3. 立つ (たつ) — senses: 1. to stand; to rise 2. to be erected 3. to depart
→
[{"meaning": "belly; (figuratively) the seat of anger", "sense": 1}, {"meaning": "subject-marking particle", "sense": 1}, {"meaning": "to rise; to well up (of anger)", "sense": 1}]

Expression: 高校生
Components:
1. 高校 (こうこう) — senses: 1. senior high school; high school
2. 生 (せい) — senses: 1. life; living 2. genuine; raw
→
[{"meaning": "high school", "sense": 1}, {"meaning": "~student; person of ~", "sense": 0}]`;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

// ── Token-cost budgeting ─────────────────────────────────────────────────────
// We meter the actual Anthropic token cost per device per day and cap it, rather
// than counting requests. Costs accumulate in micro-USD (millionths of a dollar)
// as integers — exact, no float drift in the DB.
//
// Claude Haiku 4.5 pricing ($ per million tokens). Because $1 / MTok is exactly
// 1 micro-USD per token, each $/MTok rate below IS the micro-USD-per-token rate.
const PRICE_INPUT_MICRO_USD_PER_TOK = 1.0; // $1.00 / MTok
const PRICE_OUTPUT_MICRO_USD_PER_TOK = 5.0; // $5.00 / MTok
const PRICE_CACHE_WRITE_MICRO_USD_PER_TOK = 1.25; // $1.25 / MTok (5-min ephemeral write)
const PRICE_CACHE_READ_MICRO_USD_PER_TOK = 0.1; // $0.10 / MTok (cache read)

const DEFAULT_DAILY_BUDGET_MICRO_USD = 10000; // $0.01 = 1 cent/day

function todayUtc(): string {
  return new Date().toISOString().split("T")[0];
}

function isBypassed(env: Env, deviceId: string): boolean {
  const bypass = new Set(
    (env.BYPASS_DEVICE_IDS ?? "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean)
  );
  return bypass.has(deviceId);
}

/** Cost of one Anthropic message in micro-USD, from its usage block. */
function usageCostMicroUsd(usage: {
  input_tokens?: number | null;
  output_tokens?: number | null;
  cache_creation_input_tokens?: number | null;
  cache_read_input_tokens?: number | null;
}): number {
  const input = usage.input_tokens ?? 0;
  const output = usage.output_tokens ?? 0;
  const cacheWrite = usage.cache_creation_input_tokens ?? 0;
  const cacheRead = usage.cache_read_input_tokens ?? 0;
  return Math.ceil(
    input * PRICE_INPUT_MICRO_USD_PER_TOK +
      output * PRICE_OUTPUT_MICRO_USD_PER_TOK +
      cacheWrite * PRICE_CACHE_WRITE_MICRO_USD_PER_TOK +
      cacheRead * PRICE_CACHE_READ_MICRO_USD_PER_TOK
  );
}

/**
 * Returns true if the device has already spent its daily token budget. Checked
 * BEFORE making an LLM call (cost is added afterward via recordCost). Cache hits
 * are free and should bypass this check entirely. Bypassed devices are never
 * over budget — unless they opted out of their bypass (in-app Dev Mode toggle
 * off → honorBypass=false), in which case they're metered like everyone else.
 */
async function isOverBudget(
  env: Env,
  deviceId: string,
  honorBypass = true,
  tier = 1
): Promise<boolean> {
  if (honorBypass && isBypassed(env, deviceId)) return false;
  const budget =
    parseInt(
      env.DAILY_BUDGET_MICRO_USD ?? String(DEFAULT_DAILY_BUDGET_MICRO_USD),
      10
    ) * sanitizeTier(tier);
  const row = await env.DB.prepare(
    "SELECT spent_micro_usd FROM daily_token_cost WHERE device_id = ? AND date = ?"
  )
    .bind(deviceId, todayUtc())
    .first<{ spent_micro_usd: number }>();
  return !!(row && row.spent_micro_usd >= budget);
}

/** Adds the cost of a completed LLM call to the device's daily total. */
async function recordCost(
  env: Env,
  deviceId: string,
  micros: number,
  honorBypass = true
): Promise<void> {
  if (micros <= 0 || (honorBypass && isBypassed(env, deviceId))) return;
  await env.DB.prepare(
    `INSERT INTO daily_token_cost (device_id, date, spent_micro_usd) VALUES (?, ?, ?)
     ON CONFLICT(device_id, date) DO UPDATE SET spent_micro_usd = spent_micro_usd + excluded.spent_micro_usd`
  )
    .bind(deviceId, todayUtc(), micros)
    .run();
}

function parseMeanings(text: string): string[] {
  return text
    .split("\n")
    .map((l) => l.replace(/^\d+\.\s*/, "").trim())
    .filter((l) => l.length > 0)
    .slice(0, 3);
}

function decodeCached(raw: string): string[] {
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed as string[];
  } catch {}
  return [raw]; // backward compat: old plain-string cache entries
}

function parseDisambiguation(
  text: string,
  candidates: Candidate[]
): { id: number; meaning: string } | null {
  const ids = new Set(candidates.map((c) => c.id));
  let id: number | null = null;
  let meaning: string | null = null;

  // Preferred path: a JSON object somewhere in the reply.
  const objMatch = text.match(/\{[\s\S]*\}/);
  if (objMatch) {
    try {
      const o = JSON.parse(objMatch[0]) as { id?: unknown; meaning?: unknown };
      if (typeof o.id === "number") id = o.id;
      if (typeof o.meaning === "string") meaning = o.meaning.trim();
    } catch {}
  }
  // Fallbacks for non-JSON replies.
  if (id === null) {
    const im = text.match(/"?id"?\s*[:=]\s*(\d+)/i) ?? text.match(/\b(\d+)\b/);
    if (im) id = parseInt(im[1], 10);
  }
  if (meaning === null) {
    const mm = text.match(/"?meaning"?\s*[:=]\s*"?([^"\n}]+)"?/i);
    if (mm) meaning = mm[1].trim().replace(/[",.]+$/, "");
  }

  if (id === null || !ids.has(id)) id = candidates[0].id;
  if (!meaning) return null;
  return { id, meaning };
}

async function handleDisambiguate(request: Request, env: Env): Promise<Response> {
  let body: DisambiguateRequest;
  try {
    body = (await request.json()) as DisambiguateRequest;
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }

  const { device_id, language, word, sentence, candidates } = body;
  if (
    !device_id ||
    !word ||
    !sentence ||
    !Array.isArray(candidates) ||
    candidates.length === 0
  ) {
    return json(
      { error: "Missing required fields: device_id, word, sentence, candidates" },
      400
    );
  }

  // ── Cache + budget: cache hits are free; budget is checked after a miss ─────────────────────

  // ── Cache lookup ───────────────────────────────────────────────────────────
  const cacheKey = [DISAMBIGUATE_PROMPT_VERSION, language ?? "", word, sentence].join("");
  const cached = await env.DB.prepare(
    "SELECT result FROM disambiguation_cache WHERE cache_key = ?"
  )
    .bind(cacheKey)
    .first<{ result: string }>();
  if (cached) {
    try {
      const r = JSON.parse(cached.result) as { id: number; meaning: string };
      return json({ ...r, cached: true } satisfies DisambiguateResponse);
    } catch {}
  }

  // Bring-your-own-key: when the user supplies their own Anthropic key the
  // call is billed to them — no budget check, no cost recording. The key is
  // used for this single upstream call and never stored or logged.
  const userKey =
    typeof body.api_key === "string" && body.api_key.trim().length > 0
      ? body.api_key.trim()
      : null;

  // Token-cost budget (shared daily budget with /compound). Checked only after a
  // cache miss — cache hits are free and never consume budget. dev_mode=false
  // means a dev device asked to be treated like a free user. The purchased
  // tier multiplies the budget (server-verified when Play creds are set).
  const honorBypass = body.dev_mode !== false;
  if (!userKey) {
    const tier = await effectiveTier(env, device_id, body.budget_tier);
    if (await isOverBudget(env, device_id, honorBypass, tier)) {
      return json({ error: "Daily token budget exceeded", code: "daily_limit" }, 429);
    }
  }

  // ── LLM call (separate Haiku call, single-purpose prompt) ──────────────────
  const candText = candidates
    .map((c) => `${c.id}. (${c.reading}${c.pos ? `, ${c.pos}` : ""}) ${c.gloss}`)
    .join("\n");
  const langName =
    language === "ja" ? "Japanese" : language === "zh" ? "Chinese" : "the target language";
  const userMessage = `Language: ${langName}\nSentence: ${sentence}\nWord: ${word}\nCandidate meanings:\n${candText}`;

  const client = new Anthropic({ apiKey: userKey ?? env.ANTHROPIC_API_KEY });
  let message;
  try {
    message = await client.messages.create({
      model: "claude-haiku-4-5-20251001",
      max_tokens: 128,
      system: [
        {
          type: "text",
          text: DISAMBIGUATE_SYSTEM_PROMPT,
          // NOTE: inert today — Haiku 4.5's minimum cacheable prefix is 4096 tokens
          // and this prompt is well below that, so the API silently skips caching
          // (usage.cache_*_input_tokens stay 0) and bills normal input. Kept so
          // caching activates automatically if the prompt ever grows past 4096.
          cache_control: { type: "ephemeral" },
        },
      ],
      messages: [{ role: "user", content: userMessage }],
    });
  } catch (err) {
    // A bad/revoked user key shows up as a 401 from Anthropic — tell the app
    // so it can prompt the user to fix their key instead of failing silently.
    if (userKey && err instanceof Anthropic.APIError && err.status === 401) {
      return json({ error: "Invalid API key", code: "bad_api_key" }, 401);
    }
    throw err;
  }

  // Bill the real token cost against the device's daily budget (skipped for
  // bring-your-own-key calls — those are billed to the user's key). Done before
  // the parse checks below — the tokens were spent regardless of what came back.
  if (!userKey) {
    await recordCost(env, device_id, usageCostMicroUsd(message.usage), honorBypass);
  }

  const block = message.content[0];
  if (block.type !== "text") {
    return json({ error: "Unexpected response from AI" }, 502);
  }
  const parsed = parseDisambiguation(block.text, candidates);
  if (!parsed) {
    return json({ error: "Empty response from AI" }, 502);
  }

  await env.DB.prepare(
    `INSERT INTO disambiguation_cache (cache_key, result, created_at)
     VALUES (?, ?, ?)
     ON CONFLICT(cache_key) DO NOTHING`
  )
    .bind(cacheKey, JSON.stringify(parsed), Date.now())
    .run();

  return json({ ...parsed, cached: false } satisfies DisambiguateResponse);
}

/**
 * In-expression meaning of each component of a grouped idiom / compound.
 * Cached per EXPRESSION (sentence-independent) in `expression_parts_cache`
 * (vocabulary, not user data → not swept). Mirrors /disambiguate's budget /
 * BYOK / tier handling.
 */
async function handleExpressionParts(request: Request, env: Env): Promise<Response> {
  let body: ExpressionPartsRequest;
  try {
    body = (await request.json()) as ExpressionPartsRequest;
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }

  const { device_id, expression, parts } = body;
  if (
    !device_id ||
    !expression ||
    !Array.isArray(parts) ||
    parts.length === 0 ||
    parts.length > 12
  ) {
    return json({ error: "Missing/invalid fields: device_id, expression, parts" }, 400);
  }

  // ── Cache lookup (keyed on the expression only — sentence-independent) ──────
  const cacheKey = [EXPRESSION_PARTS_PROMPT_VERSION, expression].join(" ");
  const cached = await env.DB.prepare(
    "SELECT result FROM expression_parts_cache WHERE cache_key = ?"
  )
    .bind(cacheKey)
    .first<{ result: string }>();
  if (cached) {
    try {
      const partsOut = JSON.parse(cached.result) as ExpressionPartGloss[];
      if (Array.isArray(partsOut)) {
        return json({ parts: partsOut, cached: true } satisfies ExpressionPartsResponse);
      }
    } catch {}
  }

  // UNLIMITED: in-expression / morphemic part meanings are sentence-independent
  // and cached per expression (reused across all users), so they never count
  // against the daily budget — only the sentence-specific /disambiguate does.
  // A user's own key is still honored if provided.
  const userKey =
    typeof body.api_key === "string" && body.api_key.trim().length > 0
      ? body.api_key.trim()
      : null;

  const compText = parts
    .map((p, i) => {
      const senses = (p.senses ?? [])
        .map((s, si) => `${si + 1}. ${s}`)
        .join(" ");
      return `${i + 1}. ${p.word} (${p.reading})` + (senses ? ` — senses: ${senses}` : "");
    })
    .join("\n");
  const userMessage = `Expression: ${expression}\nComponents:\n${compText}`;

  const client = new Anthropic({ apiKey: userKey ?? env.ANTHROPIC_API_KEY });
  let message;
  try {
    message = await client.messages.create({
      model: "claude-haiku-4-5-20251001",
      max_tokens: 256,
      system: [
        {
          type: "text",
          text: EXPRESSION_PARTS_SYSTEM_PROMPT,
          cache_control: { type: "ephemeral" },
        },
      ],
      messages: [{ role: "user", content: userMessage }],
    });
  } catch (err) {
    if (userKey && err instanceof Anthropic.APIError && err.status === 401) {
      return json({ error: "Invalid API key", code: "bad_api_key" }, 401);
    }
    throw err;
  }

  // No recordCost — this endpoint is unmetered (see note above).

  const block = message.content[0];
  if (block.type !== "text") {
    return json({ error: "Unexpected response from AI" }, 502);
  }
  const partsOut = parseExpressionParts(block.text, parts.length);
  if (!partsOut) {
    return json({ error: "Empty response from AI" }, 502);
  }

  await env.DB.prepare(
    `INSERT INTO expression_parts_cache (cache_key, result, created_at)
     VALUES (?, ?, ?)
     ON CONFLICT(cache_key) DO NOTHING`
  )
    .bind(cacheKey, JSON.stringify(partsOut), Date.now())
    .run();

  return json({ parts: partsOut, cached: false } satisfies ExpressionPartsResponse);
}

/** Parse the model's JSON array of {meaning, sense}; pad/truncate to [count]. */
function parseExpressionParts(text: string, count: number): ExpressionPartGloss[] | null {
  const match = text.match(/\[[\s\S]*\]/);
  if (!match) return null;
  let arr: unknown;
  try {
    arr = JSON.parse(match[0]);
  } catch {
    return null;
  }
  if (!Array.isArray(arr)) return null;
  const out: ExpressionPartGloss[] = arr.slice(0, count).map((o) => {
    const rec = (o ?? {}) as { meaning?: unknown; sense?: unknown };
    return {
      meaning: typeof rec.meaning === "string" ? rec.meaning.trim() : "",
      sense: Number.isInteger(rec.sense) ? (rec.sense as number) : 0,
    };
  });
  if (out.length === 0) return null;
  while (out.length < count) out.push({ meaning: "", sense: 0 });
  return out;
}

export default {
  /**
   * Nightly data-retention sweep (cron in wrangler.toml). User-linked data is
   * short-lived by design (Play data-safety: auto-deleted within 90 days):
   * - disambiguation_cache rows contain user-typed sentences → deleted after
   *   7 days. (Low cost: a cache hit needs the SAME sentence twice, which
   *   rarely spans a week.)
   * - daily_token_cost rows (device_id + per-day spend) → deleted once the
   *   day they meter is over a week old.
   * compound_cache is NOT swept — compounds are vocabulary, not user data,
   * and that cache is the main cost saver. device_tier persists because it IS
   * the user's purchased entitlement; deleting it would revoke their boost.
   */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const weekAgoMs = Date.now() - 7 * 24 * 60 * 60 * 1000;
    await env.DB.prepare(
      "DELETE FROM disambiguation_cache WHERE created_at < ?"
    )
      .bind(weekAgoMs)
      .run();
    const cutoffDate = new Date(weekAgoMs).toISOString().split("T")[0];
    await env.DB.prepare("DELETE FROM daily_token_cost WHERE date < ?")
      .bind(cutoffDate)
      .run();
  },

  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({ ok: true });
    }

    // ── Device status: is this a dev (bypass-listed) device, and would it be
    // over budget if metered? The app uses `dev` to decide whether to show the
    // Dev Mode toggle in the info screen. over_budget ignores the bypass so a
    // dev device can see what it would face with Dev Mode off.
    if (url.pathname === "/status" && request.method === "GET") {
      const deviceId = url.searchParams.get("device_id") ?? "";
      if (!deviceId) return json({ error: "Missing device_id" }, 400);
      return json({
        dev: isBypassed(env, deviceId),
        over_budget: await isOverBudget(env, deviceId, /* honorBypass */ false),
      });
    }

    // ── Play purchase verification (writes the device's verified tier) ──────
    if (url.pathname === "/verify_purchase" && request.method === "POST") {
      return handleVerifyPurchase(request, env);
    }

    // ── In-context sense disambiguation ──────────────────────────────────────
    if (url.pathname === "/disambiguate" && request.method === "POST") {
      return handleDisambiguate(request, env);
    }

    // ── In-expression meanings for an idiom/compound's component words ────────
    if (url.pathname === "/expression_parts" && request.method === "POST") {
      return handleExpressionParts(request, env);
    }

    if (url.pathname !== "/compound" || request.method !== "POST") {
      return json({ error: "Not found" }, 404);
    }

    let body: CompoundRequest;
    try {
      body = (await request.json()) as CompoundRequest;
    } catch {
      return json({ error: "Invalid JSON" }, 400);
    }

    const { device_id, compound, parts } = body;
    if (!device_id || !compound || !Array.isArray(parts) || parts.length === 0) {
      return json({ error: "Missing required fields: device_id, compound, parts" }, 400);
    }

    // ── Cache + budget (budget checked after a cache miss, below) ────────────────────────────────────────────────────────

    // ── Cache lookup ─────────────────────────────────────────────────────────
    const cached = await env.DB.prepare(
      "SELECT meaning FROM compound_cache WHERE compound = ?"
    )
      .bind(compound)
      .first<{ meaning: string }>();

    if (cached) {
      return json({ meanings: decodeCached(cached.meaning), cached: true } satisfies CompoundResponse);
    }

    // UNLIMITED: compound glosses are sentence-independent and cached per
    // compound (reused across all users), so they never count against the daily
    // budget — only the sentence-specific /disambiguate does. A user's own key
    // is still honored if provided.
    const userKey =
      typeof body.api_key === "string" && body.api_key.trim().length > 0
        ? body.api_key.trim()
        : null;

    // ── LLM call ─────────────────────────────────────────────────────────────
    const partsText = parts
      .map((p) => `• ${p.text} (${p.pinyin}): ${p.meaning}`)
      .join("\n");

    const userMessage = `Compound: ${compound}\nParts:\n${partsText}`;

    const client = new Anthropic({ apiKey: userKey ?? env.ANTHROPIC_API_KEY });
    let message;
    try {
      message = await client.messages.create({
        model: "claude-haiku-4-5-20251001",
        max_tokens: 96,
        system: [
          {
            type: "text",
            text: SYSTEM_PROMPT,
            // NOTE: inert today — below Haiku 4.5's 4096-token minimum cacheable
            // prefix (see same note in handleDisambiguate).
            cache_control: { type: "ephemeral" },
          },
        ],
        messages: [{ role: "user", content: userMessage }],
      });
    } catch (err) {
      if (userKey && err instanceof Anthropic.APIError && err.status === 401) {
        return json({ error: "Invalid API key", code: "bad_api_key" }, 401);
      }
      throw err;
    }

    // No recordCost — this endpoint is unmetered (see note above).

    const block = message.content[0];
    if (block.type !== "text") {
      return json({ error: "Unexpected response from AI" }, 502);
    }
    const meanings = parseMeanings(block.text);
    if (meanings.length === 0) {
      return json({ error: "Empty response from AI" }, 502);
    }

    // ── Store in cache ────────────────────────────────────────────────────────
    await env.DB.prepare(
      `INSERT INTO compound_cache (compound, meaning, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(compound) DO NOTHING`
    )
      .bind(compound, JSON.stringify(meanings), Date.now())
      .run();

    return json({ meanings, cached: false } satisfies CompoundResponse);
  },
};
