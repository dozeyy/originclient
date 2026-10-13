#!/usr/bin/env python3
"""Answers 1.8.9 API questions for .github/workflows/ref-189.yml (read that first).

usage: ref_lookup.py <jars.txt> <maps.txt> <queries.txt>
  jars.txt     one compile-classpath jar per line (MCP-named Minecraft + Forge + libraries)
  maps.txt     mapping files Loom left in the Gradle caches (*.tiny, methods/fields.csv)
  queries.txt  one query per line: `class <name>`, `member <name>`, `find <regex>`
"""
import re
import subprocess
import sys
import zipfile

MAX_LINES = 600  # per query, so one broad query can't drown the rest


def emit(lines):
    for i, line in enumerate(lines):
        if i >= MAX_LINES:
            print(f"  ... ({len(lines) - MAX_LINES} more lines cut)")
            break
        print(line)


def main():
    jars = [l.strip() for l in open(sys.argv[1]) if l.strip()]
    maps = [l.strip() for l in open(sys.argv[2]) if l.strip()]
    queries = [l.strip() for l in open(sys.argv[3]) if l.strip() and not l.strip().startswith("#")]

    classes = {}  # fqcn -> jar
    for jar in jars:
        if not jar.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(jar) as z:
                for n in z.namelist():
                    if n.endswith(".class"):
                        classes.setdefault(n[:-6].replace("/", "."), jar)
        except Exception:
            pass
    cp = ":".join(jars)

    # Tiny mappings: owner(named) -> [(kind, named, desc, srg)]
    members = {}
    for path in maps:
        if not path.endswith(".tiny"):
            continue
        try:
            with open(path, encoding="utf-8") as f:
                header = f.readline().rstrip("\n").split("\t")
                if header[:2] != ["tiny", "2"]:
                    continue
                ns = header[3:]
                named = ns.index("named") if "named" in ns else len(ns) - 1
                srg = next((ns.index(x) for x in ("srg", "searge", "intermediary") if x in ns), 0)
                owner = None
                for line in f:
                    p = line.rstrip("\n").split("\t")
                    if p[0] == "c":
                        owner = p[1 + named].replace("/", ".")
                    elif len(p) > 2 and p[0] == "" and p[1] in ("m", "f"):
                        names = p[3:]
                        if len(names) > max(named, srg):
                            members.setdefault(names[named], []).append(
                                (owner, "method" if p[1] == "m" else "field", p[2], names[srg]))
            print(f"[mappings] {path}: {header}")
        except Exception as e:
            print(f"[mappings] {path}: unreadable ({e})")
    csv_rows = []
    for path in maps:
        if path.endswith(("methods.csv", "fields.csv")):
            try:
                csv_rows += [l.rstrip("\n").split(",", 3) for l in open(path, encoding="utf-8")]
            except Exception:
                pass

    for q in queries:
        kind, _, arg = q.partition(" ")
        arg = arg.strip()
        print(f"\n===== {q} =====")
        if kind == "class":
            hits = [c for c in classes if c == arg or c.endswith("." + arg) or c.endswith("$" + arg)]
            if not hits:
                print("  (no such class)")
            for c in sorted(hits)[:6]:
                out = subprocess.run(["javap", "-p", "-cp", cp, c], capture_output=True, text=True)
                # Synthetic lambda/accessor members are noise for a port.
                emit([l for l in (out.stdout or out.stderr).splitlines()
                      if "lambda$" not in l and "access$" not in l])
        elif kind == "member":
            # Several cached tiny files repeat a member; some carry a numeric
            # namespace instead of SRG. Keep unique rows, real SRG names first.
            rows = sorted(set(members.get(arg, [])),
                          key=lambda r: not re.match(r"^(field|func)_\d+_[a-z]+$", r[3]))
            srg_rows = [r for r in rows if re.match(r"^(field|func)_\d+_[a-z]+$", r[3])]
            rows = srg_rows or rows
            for owner, k, desc, s in rows:
                print(f"  {k} {owner}.{arg} {desc}  srg={s}")
            if not rows:
                rows = [r for r in csv_rows if len(r) > 1 and r[1] == arg]
                for r in rows:
                    print(f"  csv: {','.join(r)}")
            if not rows:
                print("  (no member with that MCP name)")
        elif kind == "find":
            rx = re.compile(arg)
            emit(sorted(c for c in classes if rx.search(c) and not c.startswith(("java.", "javax.", "sun."))))
        else:
            print("  unknown query kind (use class / member / find)")


main()
