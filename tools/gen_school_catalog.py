#!/usr/bin/env python3
"""从学校目录快照生成 `app/src/main/assets/jw/schools.tsv`（选校自动填 URL 的数据源）。

用法：
    python tools/gen_school_catalog.py [源文件] [输出文件]

源文件默认读 `.tmp/jwresearch/docs_schools-list.md`（社区目录快照，不入库），
输出默认写 `app/src/main/assets/jw/schools.tsv`。

只收录「协议候选属于本应用已实现的解析器」且「有教务入口 URL」的条目：
    zf_new       -> zhengfang_new
    zf           -> zhengfang_old
    eams5        -> eams5
    qz           -> qiangzhi
    kingo_new    -> qingguo
    classic_eams -> shuwei_classic
    shuwei_json  -> shuwei_classic
    urp / urp_new/ umooc -> urp
    chaoxing     -> chaoxing
    wisedu       -> jinzhi
    cf / cf_new  -> chengfang
    yl           -> lianyi

输出列（制表符分隔，无表头）：
    name  pinyin  url  adapterId  verified

verified 为 1 表示目录标记为「已实现配置」，0 表示「待验证·可尝试」——
两者都不保证该校当前实测可用，UI 必须区分展示。
"""

import sys
import unicodedata

DEFAULT_SRC = ".tmp/jwresearch/docs_schools-list.md"
DEFAULT_OUT = "app/src/main/assets/jw/schools.tsv"

PROTOCOL_TO_ADAPTER = {
    "zf_new": "zhengfang_new",
    "zf": "zhengfang_old",
    "qz": "qiangzhi",
    "kingo_new": "qingguo",
    "classic_eams": "shuwei_classic",
    "shuwei_json": "shuwei_classic",
    "eams5": "eams5",
    "urp": "urp",
    "urp_new": "urp",
    "umooc": "urp",
    "chaoxing": "chaoxing",
    "wisedu": "jinzhi",
    "cf": "chengfang",
    "cf_new": "chengfang",
    "yl": "lianyi",
}

MIN_COLUMNS = 6


def cells(line):
    line = line.strip()
    if not line.startswith("|"):
        return None
    cols = [c.strip() for c in line.strip("|").split("|")]
    if len(cols) < MIN_COLUMNS:
        return None
    return cols


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT

    rows = []
    seen = set()
    with open(src, encoding="utf-8") as f:
        for line in f:
            cols = cells(line)
            if cols is None:
                continue
            name, status, alias, url, proto = cols[0], cols[1], cols[2], cols[3], cols[4]
            adapter = PROTOCOL_TO_ADAPTER.get(proto)
            if adapter is None or not url.startswith("http"):
                continue
            # 别名形如 "bupt / beijingyoudiandaxue"，全部收进检索串：
            # 用户可能搜缩写（hfut）也可能搜全拼（hefeigongyedaxue），两者都要能命中
            words = []
            for raw in alias.split("/"):
                word = "".join(ch for ch in raw.strip().lower() if ch.isalnum() or ch == "-")
                if word and word not in words:
                    words.append(word)
            pinyin = "/".join(words) if words else "zz"
            key = (name, url)
            if key in seen:
                continue
            seen.add(key)
            verified = "1" if "已实现" in status else "0"
            clean = name.replace("\t", " ").replace("\n", " ").strip()
            rows.append((clean, pinyin, url.strip(), adapter, verified))

    rows.sort(key=lambda r: (unicodedata.normalize("NFKD", r[1]), r[0]))

    import os
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write("\t".join(r) + "\n")

    verified = sum(1 for r in rows if r[4] == "1")
    print("wrote %d entries (%d verified, %d unverified) -> %s" % (len(rows), verified, len(rows) - verified, out))


if __name__ == "__main__":
    main()
