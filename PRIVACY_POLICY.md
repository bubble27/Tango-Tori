# Tango Tori — Privacy Policy

**Effective date: June 12, 2026**

Tango Tori ("the app") is an open-source Japanese and Chinese dictionary and
study tool developed by Alexander Davydenko ("I", "me"). This policy explains
what data the app handles, why, and for how long. The app has **no accounts,
no sign-in, no ads, and no analytics or tracking SDKs**.

## Opting out entirely

You can turn off the AI features at any time with the **"AI features"
switch** — found in the app's info dialog (ⓘ) and on the "AI usage & limits"
screen. With the switch off, **the app makes no network requests of its own
and nothing you type ever leaves your device**: no lookup content, no device
identifier, no purchase tokens. The full offline dictionary, flashcard
creation, and every non-AI feature keep working normally. (In-app purchases,
if you make them, are always handled by Google Play itself.)

## Data the app collects

### 1. Sentences and words you look up

When you use the AI-powered features (in-context meaning generation, and
compound-word interpretation for Chinese), the sentence or word you entered is
sent over HTTPS to the app's backend (a Cloudflare Worker operated by me),
which forwards it to **Anthropic** (the AI provider) to generate the result.

- Sentences are cached on the backend so repeat lookups are faster and
  cheaper, and are **automatically deleted after 7 days**.
- Interpretations of individual Chinese compound words (vocabulary, with no
  link to who looked them up) may be cached longer to keep the service fast
  and affordable.
- Sentences are **not** linked to your device identifier or to you.
- Anthropic processes this text as a service provider on my behalf and,
  per its API terms, does not use it to train its models.

If you never use the AI features — or you turn them off with the "AI
features" switch (see *Opting out entirely* above) — no lookup content leaves
your device. Regular dictionary lookups are performed entirely offline against
the bundled dictionaries.

### 2. A random device identifier

On first launch the app generates a **random ID** (a UUID). It is not derived
from your hardware, phone number, accounts, or anything personal, and it
resets if you reinstall the app. It is sent with AI requests and used to:

- enforce the daily free usage limit and prevent abuse of the AI service;
- attach a purchased usage boost to your installation.

Daily usage counters are **automatically deleted after 7 days**. A record of
your purchased usage tier is kept for as long as the entitlement exists, since
it is required to provide what you purchased.

### 3. Purchase information

If you buy a usage boost, the purchase itself is handled entirely by
**Google Play** — the app never sees your payment details. The app sends the
Play-issued purchase token to the backend, which verifies it with Google and
records your usage tier. Nothing about the purchase beyond this verified tier
is stored.

### 4. Your own Anthropic API key (optional)

If you choose to add your own Anthropic API key:

- it is stored **only on your device**, encrypted with a hardware-backed key
  in the Android Keystore;
- it is **excluded from Android backups** and device transfers;
- it is transmitted over HTTPS with your own AI requests so they can be billed
  to you, and is **never stored or logged** by the backend;
- removing it in the app, or uninstalling the app, destroys it.

## Data the app does not collect

No name, email address, location, contacts, photos, files, browsing history,
advertising identifiers, or analytics of any kind. Flashcards you create are
written directly to AnkiDroid **on your device** and never leave it.

## Third parties

| Party | Role | What they receive |
|---|---|---|
| Anthropic | AI processing (service provider) | The looked-up sentence/word; your API key only if you supplied one |
| Cloudflare | Hosts the backend and its database | The request data described above |
| Google Play | Payments and purchase verification | Purchase handled by Google itself |

Data is never sold, never used for advertising, and never shared beyond the
service providers above.

## Security

All network traffic uses HTTPS/TLS. The optional API key is encrypted at rest
with the Android Keystore. Backend secrets are stored in Cloudflare's secret
store, not in the open-source code.

## Data retention & deletion

- Looked-up sentences: deleted automatically after **7 days**.
- Daily usage counters: deleted automatically after **7 days**.
- Purchased-tier record: kept while the entitlement exists.
- Everything stored on your device is removed by clearing the app's data or
  uninstalling.

For any deletion request or privacy question, email
**alexdavydenko27@gmail.com**.

## Children

Tango Tori is a general-audience educational tool and does not knowingly
collect personal information from children; the app collects no personal
information such as names or contact details from anyone.

## Changes

If this policy changes, the updated version will be published at this same
address with a new effective date. The app's source code is public at
<https://github.com/bubble27/Tango-Tori>, so the actual data handling can
always be inspected.
