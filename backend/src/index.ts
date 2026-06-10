import Anthropic from "@anthropic-ai/sdk";

export interface Env {
  DB: D1Database;
  ANTHROPIC_API_KEY: string;
  RATE_LIMIT_PER_DAY: string;
}

interface Part {
  text: string;
  pinyin: string;
  meaning: string;
}

interface CompoundRequest {
  device_id: string;
  compound: string;
  parts: Part[];
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

const SYSTEM_PROMPT = `You are a Chinese dictionary assistant. A Chinese word was not found in the dictionary and was split into parts by a tokenizer. Your job is to give a concise English meaning for the full compound.

## Output rules — strict

Your entire reply consists of **only** a numbered list of 1 to 3 English glosses. Nothing else, ever.

**Forbidden in any reply:**
- Explanations, reasoning, commentary, or notes
- Meta-statements such as "This appears to be...", "Treating it as...", "The parts suggest...", "Possibly..."
- Refusals or any mention that the input is unfamiliar, classical, fragmentary, or unmatched
- Hedging words ("possibly," "perhaps," "uncertain," "unclear")
- Pinyin, the original Chinese, parenthetical alternatives, headers, labels, or section names
- Trailing punctuation on entries

**Required in every reply:**
- One to three lines, each beginning with \`1.\`, \`2.\`, or \`3.\`
- Each line is one short English phrase
- Most natural / most common gloss first
- Single entry when one phrase carries the full meaning; multiple entries only when English needs different wordings to capture distinct senses
- **Every input gets glossed.** No matter what the input looks like — modern, classical, literary, technical, fragmentary, ungrammatical, unfamiliar — produce a gloss composed from the parts. Never refuse. Never explain why you can't. There is no fallback that involves talking about the compound; the fallback is to gloss it anyway.

## How to gloss — general approach

Every input can be glossed. Compose meaning from the parts using this reasoning:

**1. Read the parts.** Note each part's meaning, including less common, classical, or literary senses where relevant to the input.

**2. Identify the structural relationship.** Most Chinese compounds fall into one of these:
- *Modifier + head* — first part describes the second (天气 = sky-air → weather)
- *Verb + object* — first part acts on the second (吃饭 → eat a meal)
- *Verb + complement* — second part adds result, direction, or ability to the first (喝完 → finish drinking)
- *Coordinate / parallel* — two parts of comparable weight joined in meaning (山水 → scenery)
- *Reduplication* — repeated character(s) creating a grammatical effect, not a new word
- *Fixed frame* — productive template such as 不X不Y, 又X又Y, 越X越Y, X来X去
- *Classical or literary composition* — terse, often archaic phrasing that combines parts compositionally
- *Two adjacent words the tokenizer grouped* — gloss as a natural English phrase joining their senses

**3. Let the structure shape the gloss.** When a productive grammatical pattern is present, its semantic contribution — softening, intensifying, direction, completion, ability, frame meaning — takes priority over the bare character meanings.

**4. Produce 1 to 3 glosses.** One when a single English phrase suffices. Multiple when reduplication, softening, frames, register, or aspect create real variants that English expresses with different wordings.

## Recognition aids — productive patterns

These are common patterns to recognize. They are aids, not a gate. When none applies, compose the meaning from the parts using the general approach above.

**Verb reduplication (AA, A了A, A一A, AABB, AA+object)** — softens to brief, casual, tentative.
Examples: 看看 → have a look / take a look. 想了想 → thought for a moment. 研究研究 → look into it a bit. 看看书 → do some reading / read a bit.

**Adjective reduplication (AA, AABB, ABB)** — vivid intensification, often adverbial.
Examples: 慢慢 → slowly. 高高兴兴 → cheerfully. 红彤彤 → glowing red.

**Verb + complement.**
- *Resultative* (V完, V好, V到, V见, V住, V掉, V着) — completion or attainment.
- *Directional* (V上/下/进/出/回/过/起/开, with optional 来/去) — direction of motion.
- *Potential* (V得X / V不X) — ability or inability.
Examples: 喝完 → finish drinking. 站起来 → stand up. 看得见 → can see.

**Aspectual softeners** — V一下 (briefly), V过 (experiential).
Examples: 试一下 → give it a try. 去过 → have been there.

**Fixed frames.**
- 不X不Y → neither X nor Y / just right (不冷不热 → neither hot nor cold)
- 又X又Y → both X and Y (又高又大 → tall and big)
- 越X越Y → the more X the more Y (越来越好 → better and better)
- X来X去 → repeated or back-and-forth action (走来走去 → pace back and forth)
- 一X一Y → alternating opposites (一上一下 → up and down)

**Intensifier suffixes** — X得很, X极了, X死了, X透了 → very / extremely X.
Examples: 累死了 → exhausted. 好极了 → fantastic.

**Separable verb constructions** — a verb-object compound (帮忙, 见面, 睡觉, 吃饭) split by an inserted element; reassemble the core meaning and reflect the inserted info.
Examples: 帮了忙 → helped. 见过面 → have met before.

**Productive suffixes** — X化 (-ization), X性 (-ness), X者 (-er), X家 (expert in X), X员 (member/staff), X师 (professional).
Examples: 现代化 → modernization. 可能性 → possibility. 科学家 → scientist.

## Handling unfamiliar input — still always gloss

The input may be classical (Buddhist, Confucian, Daoist, or older literary sources, often containing 此, 彼, 之, 乎, 矣, 焉, 兮, 也), a chengyu fragment, a proper noun, a place name, a technical or scientific term, onomatopoeia, a loan-word transliteration, or simply two adjacent words the tokenizer happened to chunk. In every such case, compose a gloss from the parts and output it directly. The output format never changes: 1 to 3 numbered short English phrases, nothing else.

For classical phrases, accept that the gloss may sound slightly archaic, compressed, or formal — that is correct and natural for the source material. Do not flag this; just produce the gloss.

For proper nouns or transliterations, give the name in English if recognizable, or a brief descriptive gloss if not.

For technical terms, compose the technical phrase from the parts.

## Worked examples

### Ordinary compound

Compound: 天气
Parts:
• 天 (tiān): sky, heaven
• 气 (qì): air, gas, steam

→
1. weather

### Verb reduplication with object

Compound: 看看书
Parts:
• 看 (kàn): look, see, read
• 看 (kàn): look, see, read
• 书 (shū): book

→
1. do some reading
2. read a bit
3. have a little read

### Verb + resultative complement

Compound: 喝完
Parts:
• 喝 (hē): drink
• 完 (wán): finish, complete

→
1. finish drinking
2. drink it all up

### Adjective reduplication

Compound: 高高兴兴
Parts:
• 高 (gāo): tall, high
• 高 (gāo): tall, high
• 兴 (xìng): mood, interest
• 兴 (xìng): mood, interest

→
1. cheerfully
2. happily
3. in high spirits

### Fixed frame

Compound: 不冷不热
Parts:
• 不 (bù): not
• 冷 (lěng): cold
• 不 (bù): not
• 热 (rè): hot

→
1. neither hot nor cold
2. just right in temperature
3. pleasantly mild

### Classical / literary phrase

Compound: 故彼生
Parts:
• 故 (gù): therefore, thus, because of this
• 彼 (bǐ): that, the other
• 生 (shēng): arise, be born, come into being

→
1. therefore that arises
2. thus that comes to be
3. so that is born

### Coordinate phrase the tokenizer grouped

Compound: 山水
Parts:
• 山 (shān): mountain
• 水 (shuǐ): water

→
1. landscape
2. scenery
3. mountains and rivers

### Technical / composed term

Compound: 光合作用
Parts:
• 光 (guāng): light
• 合 (hé): combine, join
• 作 (zuò): make, do
• 用 (yòng): use, function

→
1. photosynthesis`;

// Kept deliberately separate from SYSTEM_PROMPT (compound glossing) so the two
// Haiku calls never share context — each gets a clean, single-purpose prompt.
const DISAMBIGUATE_SYSTEM_PROMPT = `You are a reading assistant for Japanese and Chinese learners. You receive a sentence, a target word that appears in it, and a numbered list of candidate dictionary meanings for that word. The candidates may come from several dictionary entries (homographs with different readings).

Your job:
1. Pick the ONE candidate number whose meaning best matches how the word is actually used in the given sentence.
2. Write a concise English meaning of the word AS USED IN THIS SENTENCE — a short gloss of a few words, reflecting the contextual sense (not a full sentence, not an explanation).

## Output rules — strict
- Reply with ONLY a single JSON object: {"id": <number>, "meaning": "<short English gloss>"}
- No other text, no markdown, no code fences, no commentary.
- "id" MUST be one of the candidate numbers shown.
- "meaning" is one short English phrase, ideally under 8 words, no trailing punctuation.
- Always choose. Never refuse, never hedge.

## Examples

Language: Japanese
Sentence: 彼はクラブに入った。
Word: 入った
Candidate meanings:
1. (はいる, v5r) to enter; to go in
2. (はいる, v5r) to join (a club, company); to enroll
3. (いる, v1) to get in; to contain

→
{"id": 2, "meaning": "to join (a club)"}

Language: Chinese
Sentence: 这件衣服的料子很好。
Word: 料子
Candidate meanings:
1. (liào zi, n) material; fabric
2. (liào zi, n) the makings of (something)

→
{"id": 1, "meaning": "fabric; material"}`;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
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

  // ── Rate limiting (shared daily budget with /compound) ─────────────────────
  const today = new Date().toISOString().split("T")[0];
  const limit = parseInt(env.RATE_LIMIT_PER_DAY ?? "100", 10);
  const rl = await env.DB.prepare(
    `INSERT INTO rate_limits (device_id, date, count) VALUES (?, ?, 1)
     ON CONFLICT(device_id, date) DO UPDATE SET count = count + 1
     RETURNING count`
  )
    .bind(device_id, today)
    .first<{ count: number }>();
  if (rl && rl.count > limit) {
    return json({ error: "Rate limit exceeded" }, 429);
  }

  // ── Cache lookup ───────────────────────────────────────────────────────────
  const cacheKey = `${language ?? ""}${word}${sentence}`;
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

  // ── LLM call (separate Haiku call, single-purpose prompt) ──────────────────
  const candText = candidates
    .map((c) => `${c.id}. (${c.reading}${c.pos ? `, ${c.pos}` : ""}) ${c.gloss}`)
    .join("\n");
  const langName =
    language === "ja" ? "Japanese" : language === "zh" ? "Chinese" : "the target language";
  const userMessage = `Language: ${langName}\nSentence: ${sentence}\nWord: ${word}\nCandidate meanings:\n${candText}`;

  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
  const message = await client.messages.create({
    model: "claude-haiku-4-5-20251001",
    max_tokens: 128,
    system: [
      {
        type: "text",
        text: DISAMBIGUATE_SYSTEM_PROMPT,
        cache_control: { type: "ephemeral" },
      },
    ],
    messages: [{ role: "user", content: userMessage }],
  });

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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({ ok: true });
    }

    // ── In-context sense disambiguation ──────────────────────────────────────
    if (url.pathname === "/disambiguate" && request.method === "POST") {
      return handleDisambiguate(request, env);
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

    // ── Rate limiting ────────────────────────────────────────────────────────
    const today = new Date().toISOString().split("T")[0];
    const limit = parseInt(env.RATE_LIMIT_PER_DAY ?? "100", 10);

    const rl = await env.DB.prepare(
      `INSERT INTO rate_limits (device_id, date, count) VALUES (?, ?, 1)
       ON CONFLICT(device_id, date) DO UPDATE SET count = count + 1
       RETURNING count`
    )
      .bind(device_id, today)
      .first<{ count: number }>();

    if (rl && rl.count > limit) {
      return json({ error: "Rate limit exceeded" }, 429);
    }

    // ── Cache lookup ─────────────────────────────────────────────────────────
    const cached = await env.DB.prepare(
      "SELECT meaning FROM compound_cache WHERE compound = ?"
    )
      .bind(compound)
      .first<{ meaning: string }>();

    if (cached) {
      return json({ meanings: decodeCached(cached.meaning), cached: true } satisfies CompoundResponse);
    }

    // ── LLM call ─────────────────────────────────────────────────────────────
    const partsText = parts
      .map((p) => `• ${p.text} (${p.pinyin}): ${p.meaning}`)
      .join("\n");

    const userMessage = `Compound: ${compound}\nParts:\n${partsText}`;

    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
    const message = await client.messages.create({
      model: "claude-haiku-4-5-20251001",
      max_tokens: 96,
      system: [
        {
          type: "text",
          text: SYSTEM_PROMPT,
          cache_control: { type: "ephemeral" },
        },
      ],
      messages: [{ role: "user", content: userMessage }],
    });

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
