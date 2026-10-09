#!/usr/bin/env python3
"""Checks every translation against values/strings.xml: same keys, same placeholders,
plural forms present, apostrophes escaped. Exit code 1 on any problem.

    python3 -I scripts/l10n-check.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(\d+\$)?[sd]")
# Languages that only have the "other" plural category.
ONLY_OTHER = {"ja", "zh"}


def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        name = el.get("name")
        if el.tag == "string" and el.get("translatable") != "false":
            strings[name] = (el.text or "", el.get("formatted"))
        elif el.tag == "plurals":
            plurals[name] = {i.get("quantity"): i.text or "" for i in el}
    return strings, plurals


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text))


def raw_apostrophes(path):
    """Unescaped ' inside <string> bodies; aapt2 rejects or mangles them."""
    bad = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        body = re.sub(r"<[^>]+>", "", line)
        if re.search(r"(?<!\\)'", body):
            bad.append(n)
    return bad


def main():
    base_strings, base_plurals = load(RES / "values/strings.xml")
    problems = 0
    for path in sorted(RES.glob("values-*/strings.xml")):
        lang = path.parent.name.removeprefix("values-")
        strings, plurals = load(path)
        issues = []
        missing = set(base_strings) - set(strings)
        extra = set(strings) - set(base_strings)
        if missing:
            issues.append(f"missing: {', '.join(sorted(missing))}")
        if extra:
            issues.append(f"extra: {', '.join(sorted(extra))}")
        for key, (text, formatted) in strings.items():
            if key not in base_strings:
                continue
            base_text, base_formatted = base_strings[key]
            if placeholders(text) != placeholders(base_text):
                issues.append(f"{key}: placeholders {placeholders(text)} != {placeholders(base_text)}")
            if base_formatted == "false" and formatted != "false":
                issues.append(f'{key}: needs formatted="false"')
            if "%" in PLACEHOLDER.sub("", text) and formatted != "false":
                issues.append(f"{key}: literal % needs formatted=\"false\"")
        for key, forms in base_plurals.items():
            got = plurals.get(key)
            if got is None:
                issues.append(f"plurals {key}: missing")
                continue
            needed = {"other"} if lang.split("-")[0] in ONLY_OTHER else {"one", "other"}
            if not needed <= set(got):
                issues.append(f"plurals {key}: needs {sorted(needed)}, has {sorted(got)}")
            for q, text in got.items():
                if placeholders(text) != placeholders(forms["other"]):
                    issues.append(f"plurals {key}/{q}: placeholders differ")
        lines = raw_apostrophes(path)
        if lines:
            issues.append(f"unescaped apostrophe on lines {lines}")
        print(f"{lang:8} {'ok' if not issues else f'{len(issues)} problem(s)'}")
        for i in issues:
            print(f"         {i}")
        problems += len(issues)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
