"""Checks the string resources of both modules: English (values/) and Spanish (values-es/) have the same keys and
placeholders, no key is defined twice, and every R.string / R.plurals / R.array the code uses exists.
Run from the repo root: python scripts/check_strings.py"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

PH = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfx%]")


def load(folder):
    out = {}
    for f in sorted(folder.glob("*.xml")):
        for e in ET.parse(f).getroot():
            if e.tag not in ("string", "plurals", "string-array"):
                continue
            k = (e.tag, e.get("name"))
            if k in out:
                raise SystemExit(f"duplicate {k} in {f.name} and {out[k][0]}")
            text = "".join(e.itertext())
            out[k] = (f.name, sorted(m.group(0) for m in PH.finditer(text) if m.group(0) != "%%"))
    return out


bad = 0
for module in ("app", "wear"):
    res = pathlib.Path(module, "src/main/res")
    en, es = load(res / "values"), load(res / "values-es")
    for k in en.keys() - es.keys():
        print(f"{module}: missing in values-es: {k[1]}"); bad += 1
    for k in es.keys() - en.keys():
        print(f"{module}: only in values-es: {k[1]}"); bad += 1
    for k in en.keys() & es.keys():
        if k[0] == "string" and en[k][1] != es[k][1]:
            print(f"{module}: placeholders differ in {k[1]}: {en[k][1]} vs {es[k][1]}"); bad += 1
    kinds = {"string": "string", "plurals": "plurals", "array": "string-array"}
    used = set()
    for src in pathlib.Path(module, "src").rglob("*.kt"):
        used |= {(kinds[t], n) for t, n in re.findall(r"\bR\.(string|plurals|array)\.(\w+)", src.read_text(encoding="utf-8"))}
    for src in pathlib.Path(module, "src/main").rglob("*.xml"):
        used |= {("string", n) for n in re.findall(r"@string/(\w+)", src.read_text(encoding="utf-8"))}
    for k in sorted(used - en.keys()):
        print(f"{module}: used but not defined: {k[1]}"); bad += 1
    print(f"{module}: {len(en)} resources")

sys.exit(1 if bad else 0)
