#!/usr/bin/env python3
"""Static check: does every Origin Sodium/Iris injector's target (method, field, @At INVOKE/FIELD target)
still exist in a given Sodium/Iris jar pair?  usage: spike-sigcheck.py <module-dir> <sodium.jar> <iris.jar> [label]
Name-level for method=, exact descriptor for @At targets and fields. Prints MISSING lines + a summary."""
import re, sys, os, zipfile, subprocess, tempfile, glob

JAVAP = r"C:\Program Files\Java\jdk-21.0.10\bin\javap.exe"
module, sod, iris = sys.argv[1:4]
label = sys.argv[4] if len(sys.argv) > 4 else ""

# --- collect specs from the module's Sodium*/Iris* mixins -------------------------------------------------
specs = []   # (mixinfile, targetFQN, kind, method, atTarget, field)
for fn in sorted(glob.glob(os.path.join(module, "src/client/java/com/origin/client/client/mixin/*.java"))):
    raw = open(fn, encoding="utf-8", errors="replace").read()
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", raw, flags=re.S)
    if not re.search(r"caffeinemc|irisshaders|jellysquid|coderbot", code):
        continue
    name = os.path.basename(fn)[:-5]
    m = re.search(r"@Mixin\((.*?)\)\s*(?:public |abstract )*(?:class|interface)", code, re.S)
    if not m:
        continue
    head = m.group(1)
    quoted = re.findall(r'"([\w.$]+)"', head) if "targets" in head else []
    if quoted:
        tgt = quoted[0]
        for q in quoted:
            if any(q.replace(".", "/") + ".class" in z.namelist() for z in (zipfile.ZipFile(sod), zipfile.ZipFile(iris))):
                tgt = q
                break
    else:
        vm = re.search(r"value\s*=\s*(\w+)\.class", head)
        if not vm:
            continue
        simple = vm.group(1)
        imp = re.search(r"import ([\w.]+\." + simple + r");", code)
        if not imp:
            continue
        tgt = imp.group(1)
    # annotations with method=
    for a in re.finditer(r"@(Inject|Redirect|ModifyExpressionValue|ModifyReturnValue|WrapOperation|ModifyArg|ModifyVariable)\s*\((.*?)\)\s*(?=\n\s*(?:private|public|protected|static|default))", code, re.S):
        body = a.group(2)
        mm = re.search(r'method\s*=\s*"([^"]+)"', body)
        at = re.search(r'target\s*=\s*"(L[^"]+)"', body)
        specs.append((name, tgt, a.group(1), mm.group(1) if mm else None, at.group(1) if at else None, None))
    for a in re.finditer(r'@Accessor\("(\w+)"\)', code):
        specs.append((name, tgt, "Accessor", None, None, a.group(1)))
    for a in re.finditer(r"@Shadow\s*(?:@Final\s*)?(?:@Mutable\s*)?(?:private|protected|public)?\s*(?:final\s*)?[\w<>\[\], .]+?\s+(\w+)\s*;", code):
        specs.append((name, tgt, "Shadow", None, None, a.group(1)))

# --- javap the needed classes -------------------------------------------------------------------------------
tmp = tempfile.mkdtemp(prefix="sigchk")
zs = {"sod": zipfile.ZipFile(sod), "iris": zipfile.ZipFile(iris)}
cache = {}


def dump(fqn):
    if fqn in cache:
        return cache[fqn]
    path = fqn.replace(".", "/") + ".class"
    for z in zs.values():
        if path in z.namelist():
            d = os.path.join(tmp, fqn)
            os.makedirs(d, exist_ok=True)
            z.extract(path, d)
            out = subprocess.run([JAVAP, "-p", "-c", "-cp", d, fqn], capture_output=True, text=True, errors="replace").stdout
            cache[fqn] = out
            return out
    cache[fqn] = None
    return None


def methods(out):
    """{name: [body,...]} split on javap -c method headers."""
    res = {}
    cur = None
    for line in out.splitlines():
        h = re.match(r"\s{2}(?:[\w<>\[\], .$]+\s)?(\w+|<init>|<clinit>)\(.*\)(?:\s+throws .*)?;$", line)
        if h and not line.startswith("    "):
            cur = h.group(1)
            res.setdefault(cur, []).append("")
        elif cur:
            res[cur][-1] += line + "\n"
    return res


def at_present(body, at):
    # at = Lowner;name(desc)ret   or   Lowner;name:Ldesc;
    m = re.match(r"L([^;]+);(\w+)(\(.*)", at)
    if m:
        owner, n, desc = m.groups()
        return (f"{owner}.{n}:{desc}" in body) or (f"Method {n}:{desc}" in body)
    m = re.match(r"L([^;]+);(\w+):(.+)", at)
    if m:
        owner, n, desc = m.groups()
        return (f"{owner}.{n}:{desc}" in body) or (f"Field {n}:{desc}" in body)
    return None


missing, ok = [], 0
for (mix, tgt, kind, meth, at, fld) in specs:
    out = dump(tgt)
    if out is None:
        missing.append(f"{mix}: target class {tgt} ABSENT")
        continue
    if kind in ("Accessor", "Shadow"):
        if re.search(r"\b" + fld + r";", out):
            ok += 1
        else:
            missing.append(f"{mix}: field {tgt.split('.')[-1]}.{fld} ABSENT")
        continue
    ms = methods(out)
    if meth not in ms:
        missing.append(f"{mix}: {kind} method {tgt.split('.')[-1]}.{meth} ABSENT")
        continue
    if at:
        hit = any(at_present(b, at) for b in ms[meth])
        if not hit:
            missing.append(f"{mix}: {kind} {meth} no longer contains {at.split(';')[1][:70]}")
            continue
    ok += 1
print(f"sigcheck {label}: {ok} injectors/fields resolve, {len(missing)} do NOT (of {len(specs)})")
for m in missing:
    print("  MISSING", m)
