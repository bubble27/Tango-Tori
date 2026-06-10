"""
Match mnemonic image filenames in a local image folder to Heisig keyword spellings,
then rename each file so its base name exactly matches the keyword.

Matching priority:
  1. Exact match (case-insensitive) on raw stem
  2. Exact match on stem after stripping trailing digits / underscores / junk suffixes
  3. Fuzzy match, accepted only when ALL of:
       - SequenceMatcher ratio >= FUZZY_THRESHOLD
       - len(shorter) / len(longer) >= LENGTH_RATIO_MIN
       - neither string is a prefix or suffix of the other (blocks "arm"/"army",
         "along"/"long", "arrow"/"narrow", "bone"/"one", etc.)

Files with no good match are left untouched and reported.
When multiple files map to the same keyword, the file whose stem is already
the exact keyword gets the base slot; duplicates get numeric suffixes.
"""

import re
import difflib
from pathlib import Path

# Set these to your own local paths before running.
MEMES_DIR          = Path(r"./mnemonic_images")
TSV_PATH           = Path(r"./simplified_heisig_waog.tsv")
FUZZY_THRESHOLD    = 0.90
LENGTH_RATIO_MIN   = 0.85
IMAGE_EXTS         = {".jpg", ".jpeg", ".png", ".gif", ".jfif"}
DRY_RUN            = False

# Stem → exact keyword overrides (corrections below the fuzzy threshold)
MANUAL_OVERRIDES: dict[str, str] = {
    "criticise": "criticize",
    "desease":   "disease",
    "releive":   "relieve",
    "universar": "universal",
    "widoved":   "widowed",
}


# ── load keywords ─────────────────────────────────────────────────────────────

def load_keywords(tsv: Path) -> list[str]:
    keywords = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4:
                keywords.append(parts[3])
    return keywords


# ── stem normalisation ────────────────────────────────────────────────────────

_JUNK_RE = re.compile(
    r"(\.jpg\d+)"       # ".jpg5" artefact
    r"|(_[a-z]{1,3})$"  # "_it", "_s", "_es" suffix
    r"|(\s*\d+)$",      # trailing digits (with optional leading space)
    re.IGNORECASE,
)

def strip_junk(name: str) -> str:
    return _JUNK_RE.sub("", name).strip()

def norm(s: str) -> str:
    return s.lower().strip()


# ── fuzzy match guard ─────────────────────────────────────────────────────────

def is_prefix_or_suffix(a: str, b: str) -> bool:
    """True if the shorter string is a prefix or suffix of the longer."""
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    return longer.startswith(shorter) or longer.endswith(shorter)


# ── matching ──────────────────────────────────────────────────────────────────

def best_match(stem: str, kw_lower: list[str], keywords: list[str]) -> tuple[str | None, float]:
    n_raw = norm(stem)
    n_clean = norm(strip_junk(stem))

    # phase 0: manual overrides
    for query in (n_raw, n_clean):
        if query in MANUAL_OVERRIDES:
            target_kw = MANUAL_OVERRIDES[query]
            if target_kw in keywords:
                return target_kw, 1.0

    # phase 1: exact on raw stem
    for i, kl in enumerate(kw_lower):
        if n_raw == kl:
            return keywords[i], 1.0

    # phase 2: exact on stripped stem
    if n_clean and n_clean != n_raw:
        for i, kl in enumerate(kw_lower):
            if n_clean == kl:
                return keywords[i], 1.0

    # phase 3: fuzzy on stripped stem (guards: ratio, length ratio, no prefix/suffix)
    query = n_clean or n_raw
    if not query:
        return None, 0.0

    candidates = difflib.get_close_matches(query, kw_lower, n=5, cutoff=FUZZY_THRESHOLD)
    for m in candidates:
        # length ratio guard
        lo, hi = sorted([len(query), len(m)])
        if hi == 0 or lo / hi < LENGTH_RATIO_MIN:
            continue
        # prefix/suffix guard
        if is_prefix_or_suffix(query, m):
            continue
        i = kw_lower.index(m)
        score = difflib.SequenceMatcher(None, query, kw_lower[i]).ratio()
        return keywords[i], score

    return None, 0.0


# ── conflict-safe target path ─────────────────────────────────────────────────

def safe_target(directory: Path, keyword: str, ext: str, used: set[str]) -> Path:
    key = norm(keyword) + norm(ext)
    if key not in used:
        return directory / (keyword + ext)
    n = 2
    while True:
        key = f"{norm(keyword)} ({n}){norm(ext)}"
        if key not in used:
            return directory / f"{keyword} ({n}){ext}"
        n += 1


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    keywords = load_keywords(TSV_PATH)
    kw_lower = [norm(k) for k in keywords]

    files = sorted(
        p for p in MEMES_DIR.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS
    )

    print(f"Found {len(files)} image files")
    print(f"Loaded {len(keywords)} Heisig keywords\n")

    # pass 1: compute best keyword match for every file
    matched: list[tuple[Path, str, float]] = []
    no_match: list[Path] = []

    for f in files:
        kw, score = best_match(f.stem, kw_lower, keywords)
        if kw is None:
            no_match.append(f)
        else:
            matched.append((f, kw, score))

    # pass 2: assign unique target names
    # Sort: by keyword → higher score first → exact-stem matches first → filename
    def sort_key(item: tuple[Path, str, float]):
        src, kw, score = item
        is_exact = norm(src.stem) == norm(kw)
        return (norm(kw), -score, not is_exact, src.name)

    matched.sort(key=sort_key)

    used_keys: set[str] = set()
    plan: list[tuple[Path, Path]] = []

    for src, kw, score in matched:
        target = safe_target(MEMES_DIR, kw, src.suffix, used_keys)
        used_keys.add(norm(target.stem) + norm(target.suffix))

        if target.resolve() == src.resolve():
            continue  # already correctly named
        plan.append((src, target))

    # pass 3: execute or preview
    label = "DRY RUN - " if DRY_RUN else ""
    print(f"=== {label}Renaming {len(plan)} files ===\n")

    renamed = 0
    errors: list[str] = []

    for src, dst in plan:
        if DRY_RUN:
            print(f"  {src.name!r:60s} -> {dst.name!r}")
            renamed += 1
            continue
        try:
            if dst.exists() and dst.resolve() != src.resolve():
                errors.append(f"SKIP (dst exists): {src.name} -> {dst.name}")
                continue
            src.rename(dst)
            print(f"  {src.name!r:60s} -> {dst.name!r}")
            renamed += 1
        except Exception as e:
            errors.append(f"ERROR {src.name}: {e}")

    verb = "Would rename" if DRY_RUN else "Renamed"
    print(f"\n{verb}: {renamed}")

    if errors:
        print(f"\n=== {len(errors)} errors ===")
        for e in errors:
            print(f"  {e}")

    print(f"\n=== {len(no_match)} files with no keyword match ===")
    for f in no_match:
        print(f"  {f.name}")


if __name__ == "__main__":
    main()
