#!/usr/bin/env python3
"""
build_cedict.py — builds cedict.db for the Tango Tori Android app.

Usage
-----
1. Download CC-CEDICT from https://www.mdbg.net/chinese/dictionary?page=cc-cedict
   Save it as cedict_ts.u8 (the default download name) next to this script.

2. Run:
       python build_cedict.py

3. The output cedict.db will appear in app/src/main/assets/cedict.db.
   Rebuild the app after this step.

CC-CEDICT license: Creative Commons Attribution-Share Alike 3.0
https://creativecommons.org/licenses/by-sa/3.0/
"""

import re
import sqlite3
import os
import sys

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CEDICT_INPUT = os.path.join(SCRIPT_DIR, "cedict_ts.u8")
OUTPUT_DB = os.path.join(SCRIPT_DIR, "app", "src", "main", "assets", "cedict.db")

# ── HSK 1-6 word lists (new 2021 standard not yet universal — using HSK 1-6) ──
# These are the canonical HSK word lists. Simplified Chinese forms only.

HSK_WORDS: dict[str, str] = {}

HSK_1 = """
一 二 三 四 五 六 七 八 九 十 零 百 千 万 人 什么 不 的 是 我 你 他 她 它 我们 你们 他们 她们 这 那
里 上 下 有 没有 大 小 多 少 好 吃 喝 看 听 说 来 去 在 和 也 都 很 吗 呢 啊 嗯 对 不对 中国 北京
学生 老师 朋友 妈妈 爸爸 哥哥 姐姐 弟弟 妹妹 儿子 女儿 先生 小姐 家 学校 工作 汉语 中文 英文
今天 明天 昨天 年 月 日 星期 时 分 钱 块 毛 分 高兴 谢谢 再见 你好 不客气 对不起 没关系
""".split()

HSK_2 = """
一下 一点 一起 一直 上班 上面 不但 不过 一样 东西 事情 以后 以前 出来 出去 出发 刚才 别 到 只
告诉 回来 回去 回家 地方 城市 外面 帮助 常常 应该 快 慢 已经 开始 开门 关门 旁边 时候 没 每天
比较 特别 真 终于 结束 终于 要 过 发现 认识 知道 觉得 喜欢 想 能 会 可以 需要 应该 打算 准备
""".split()

HSK_3 = """
一般 一定 一共 一切 不管 不仅 不必 介绍 以为 其实 关系 其他 参加 反对 只有 叫 只要 另外 可能
同意 向 如果 安静 完成 对面 就是 居然 情况 意思 按照 支持 方面 方法 决定 放 比较 水平 然后 经过
经常 而且 联系 努力 放心 注意 理解 认为 邀请 除了 附近 难 容易 竟然 突然 紧张 简单 复杂 重要
目的 共同 关于 及时 努力 或者 愿意 应该 不同 打算 计划 将来 希望 害怕 奇怪 提高
""".split()

HSK_4 = """
一方面 不得不 之后 之前 之间 代替 以为 使 例如 体现 充分 分析 判断 到底 功能 升 卷 养成 具有
包含 包括 参考 受到 困难 坚持 实际 实现 实验 恢复 情感 推迟 推荐 探讨 提供 效果 数量 比例 决心
矛盾 社会 经济 统计 解决 表示 评价 证明 关注 展示 现代 结构 网络 影响 积极 积累 联系 行为 观点
""".split()

HSK_5 = """
一旦 不妨 万一 上述 中间 专业 假设 假如 保持 促进 典型 典型 出现 制度 制造 前提 劳动 合理 国际
在意 外观 宽广 展现 意义 推广 描述 条件 规律 规划 认可 资源 辩论 逻辑 运用 限制 风险 整体 措施
发展 领域 阶段 非常 环境 表达 结论 综合 考虑 适当 转变 方式 策略 体系 调整 理论 分析 建立 确定
""".split()

HSK_6 = """
一概 不言而喻 交融 体制 元素 内涵 内部 公认 共识 典型 基础 学科 宏观 对策 市场 应对 机制 模型
框架 规范 演变 联合 视角 价值 资源 社区 取向 定性 基准 权利 趋势 维度 理念 影响因素 动态 变革
实证 系统 分工 指导 融合 核心 激励 特征 论证 结合 文化 政策 管理 方向 解析 方案 协调 目标 功能
""".split()

for word in HSK_1:
    HSK_WORDS[word] = "HSK 1"
for word in HSK_2:
    if word not in HSK_WORDS:
        HSK_WORDS[word] = "HSK 2"
for word in HSK_3:
    if word not in HSK_WORDS:
        HSK_WORDS[word] = "HSK 3"
for word in HSK_4:
    if word not in HSK_WORDS:
        HSK_WORDS[word] = "HSK 4"
for word in HSK_5:
    if word not in HSK_WORDS:
        HSK_WORDS[word] = "HSK 5"
for word in HSK_6:
    if word not in HSK_WORDS:
        HSK_WORDS[word] = "HSK 6"

# ── Pinyin number → tone-mark conversion ─────────────────────────────────────

_TONE_MARKS = {
    'a': ['ā', 'á', 'ǎ', 'à'],
    'e': ['ē', 'é', 'ě', 'è'],
    'i': ['ī', 'í', 'ǐ', 'ì'],
    'o': ['ō', 'ó', 'ǒ', 'ò'],
    'u': ['ū', 'ú', 'ǔ', 'ù'],
    'ü': ['ǖ', 'ǘ', 'ǚ', 'ǜ'],
}


def _find_tone_pos(syllable: str) -> int:
    """Return the index of the vowel that gets the tone mark."""
    s = syllable.lower()
    for v in ('a', 'e'):
        idx = s.find(v)
        if idx >= 0:
            return idx
    idx = s.find('ou')
    if idx >= 0:
        return idx
    for i in range(len(s) - 1, -1, -1):
        if s[i] in 'iouü':
            return i
    return -1


def convert_syllable(syllable: str) -> str:
    """Convert a single number-tone syllable to tone-mark form."""
    if not syllable:
        return syllable
    if not syllable[-1].isdigit():
        return syllable
    tone = int(syllable[-1])
    base = syllable[:-1].replace('u:', 'ü').replace('v', 'ü')
    if tone == 5 or tone == 0:
        return base
    pos = _find_tone_pos(base)
    if pos < 0:
        return base
    vowel = base[pos].lower()
    marks = _TONE_MARKS.get(vowel, [])
    if not marks or tone < 1 or tone > 4:
        return base
    marked = marks[tone - 1]
    if base[pos].isupper():
        marked = marked.upper()
    return base[:pos] + marked + base[pos + 1:]


def convert_pinyin(pinyin_numbers: str) -> str:
    """Convert space-separated number-tone pinyin string to tone marks."""
    return ' '.join(convert_syllable(s) for s in pinyin_numbers.strip().split())


# ── CC-CEDICT parser ──────────────────────────────────────────────────────────

LINE_RE = re.compile(
    r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/$'
)


def parse_cedict(path: str):
    """Yield (traditional, simplified, pinyin_numbers, definitions_list) tuples."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            m = LINE_RE.match(line)
            if not m:
                continue
            traditional, simplified, pinyin_raw, defs_raw = m.groups()
            # Skip entries that are purely latin (romanisations, abbreviations).
            if all(ord(c) < 0x3000 or c.isspace() for c in simplified):
                continue
            defs = [d.strip() for d in defs_raw.split('/') if d.strip()]
            yield traditional, simplified, pinyin_raw, defs


# ── Database builder ─────────────────────────────────────────────────────────

def build(input_path: str, output_path: str) -> None:
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    c = conn.cursor()

    c.executescript("""
        CREATE TABLE cedict_entry (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            traditional    TEXT    NOT NULL,
            simplified     TEXT    NOT NULL,
            pinyin_numbers TEXT    NOT NULL,
            pinyin_marks   TEXT    NOT NULL,
            hsk_level      TEXT,
            is_common      INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE cedict_sense (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_id   INTEGER NOT NULL REFERENCES cedict_entry(id),
            gloss      TEXT    NOT NULL
        );
        CREATE INDEX idx_cedict_simplified  ON cedict_entry(simplified);
        CREATE INDEX idx_cedict_traditional ON cedict_entry(traditional);
        CREATE INDEX idx_cedict_sense_entry ON cedict_sense(entry_id);
    """)

    entry_rows = []
    sense_pairs = []  # (entry_index_in_list, gloss)
    entry_id = 0

    print(f"Parsing {input_path} …")
    for traditional, simplified, pinyin_numbers, defs in parse_cedict(input_path):
        pinyin_marks = convert_pinyin(pinyin_numbers)
        hsk = HSK_WORDS.get(simplified)
        is_common = 1 if hsk in ('HSK 1', 'HSK 2', 'HSK 3') else 0
        entry_id += 1
        entry_rows.append((entry_id, traditional, simplified, pinyin_numbers, pinyin_marks, hsk, is_common))
        for g in defs:
            sense_pairs.append((entry_id, g))

        if entry_id % 20000 == 0:
            print(f"  {entry_id} entries …")

    print(f"Inserting {entry_id} entries …")
    c.executemany(
        "INSERT INTO cedict_entry (id, traditional, simplified, pinyin_numbers, pinyin_marks, hsk_level, is_common) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        entry_rows,
    )
    print(f"Inserting {len(sense_pairs)} senses …")
    c.executemany(
        "INSERT INTO cedict_sense (entry_id, gloss) VALUES (?, ?)",
        sense_pairs,
    )

    conn.commit()
    conn.close()
    size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"Done → {output_path}  ({size_mb:.1f} MB)")


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    if not os.path.exists(CEDICT_INPUT):
        print(f"ERROR: CC-CEDICT file not found at:\n  {CEDICT_INPUT}\n")
        print("Download it from https://www.mdbg.net/chinese/dictionary?page=cc-cedict")
        print("Save the extracted cedict_ts.u8 file next to this script, then re-run.")
        sys.exit(1)
    build(CEDICT_INPUT, OUTPUT_DB)
