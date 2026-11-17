"""Warn/error lines only present in the second log (timestamps and numbers stripped). usage: errdiff.py <gl.log> <vk.log>"""
import re, sys
def lines(p):
    out = set()
    for ln in open(p, encoding="utf-8", errors="replace"):
        if re.search(r"/(ERROR|WARN)\]", ln):
            ln = re.sub(r"^\[[0-9:]+\] ", "", ln.strip())
            ln = re.sub(r"\d+", "N", ln)[:170]
            out.add(ln)
    return out
a, b = lines(sys.argv[1]), lines(sys.argv[2])
skip = re.compile(r"Neutralized|Vulkairis/(COMPAT|RESOURCE)|shaderpack|Method overwrite conflict|Error loading class")
for ln in sorted(b - a):
    if not skip.search(ln):
        print(ln)
