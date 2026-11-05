#!/usr/bin/env python3
"""Dead-hook gate for the unobfuscated 26.x modules (LV-4).

    python3 tools/ci/check_mixin_targets.py <module dir> [--jar <minecraft jar> ...]

Origin's mixin configs are defaultRequire 0 (fail-soft), so a hook whose target method was renamed or
removed compiles, boots and silently does nothing. On the 1.x modules build-mods.yml catches that from
Loom's remap output ("Cannot remap ... does not exist in any of the targets"). 26.x is unobfuscated:
nothing is remapped, that line never appears, and the old gate passed without checking anything.

This gate reads the Minecraft jar the build just used and checks, for every mixin listed in the
module's *.mixins.json that targets a Minecraft class (net/minecraft, com/mojang):
  - the @Mixin target class exists;
  - every injector's method = "name" is declared in a target class;
  - every @At / @Slice target = "Lowner;name(...)" / "Lowner;name:type" exists on that owner or a
    supertype of it (a call through a subclass names the subclass as owner);
  - every @Accessor / @Invoker name exists in a target class.
It fails loudly when it cannot find the jar, so 26.x is never "checked" by checking nothing.

The jar is found in Loom's caches by the module's minecraft_version (gradle.properties): Loom keeps the
official client jar at ~/.gradle/caches/fabric-loom/<version>/minecraft-client.jar, and its split
minecraft-common/-clientOnly jars under minecraftMaven; on 26.x those already carry Mojang's names.
Pure Python (its own class-file reader): no javap, no JDK needed.
"""
import argparse
import glob
import json
import os
import re
import struct
import sys
import zipfile

MC_PREFIXES = ("net/minecraft/", "com/mojang/")
OBJECT_METHODS = {"<init>", "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait",
                  "clone", "finalize"}


# ---- class files -------------------------------------------------------------------------------

class ClassInfo:
    __slots__ = ("name", "super", "interfaces", "methods", "fields")

    def __init__(self, name, sup, interfaces, methods, fields):
        self.name = name
        self.super = sup
        self.interfaces = interfaces
        self.methods = methods
        self.fields = fields


def read_class(data):
    """Parses a .class file's name, supertypes and member names (constant pool walk, nothing else)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = {}
    classes = {}
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (n,) = struct.unpack_from(">H", data, pos)
            utf8[i] = data[pos + 2:pos + 2 + n].decode("utf-8", "replace")
            pos += 2 + n
        elif tag in (7, 8, 16, 19, 20):
            if tag == 7:
                classes[i] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1   # longs and doubles take two slots
        else:
            raise ValueError("bad constant pool tag %d" % tag)
        i += 1

    def cls(idx):
        return utf8[classes[idx]] if idx else None

    _access, this_idx, super_idx, n_if = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    interfaces = []
    for _ in range(n_if):
        interfaces.append(cls(struct.unpack_from(">H", data, pos)[0]))
        pos += 2

    def members():
        nonlocal pos
        (n,) = struct.unpack_from(">H", data, pos)
        pos += 2
        names = set()
        for _ in range(n):
            _acc, name_idx, _desc, n_attr = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            names.add(utf8[name_idx])
            for _ in range(n_attr):
                (length,) = struct.unpack_from(">I", data, pos + 2)
                pos += 6 + length
        return names

    fields = members()
    methods = members()
    return ClassInfo(cls(this_idx), cls(super_idx), interfaces, methods, fields)


class JarIndex:
    """Minecraft's classes, read lazily from one or more jars (the first jar holding a class wins)."""

    def __init__(self, jars):
        self.zips = [zipfile.ZipFile(j) for j in jars]
        self.where = {}
        for z in self.zips:
            for n in z.namelist():
                if n.endswith(".class") and n.startswith(MC_PREFIXES):
                    self.where.setdefault(n[:-6], z)
        self.cache = {}

    def get(self, name):
        if name not in self.cache:
            z = self.where.get(name)
            self.cache[name] = read_class(z.read(name + ".class")) if z else None
        return self.cache[name]

    def __len__(self):
        return len(self.where)


# ---- finding the jar ---------------------------------------------------------------------------

def minecraft_version(module_dir):
    with open(os.path.join(module_dir, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*minecraft_version\s*=\s*(\S+)", line)
            if m:
                return m.group(1)
    return None


def find_jars(module_dir, version):
    roots = [os.path.expanduser("~/.gradle/caches/fabric-loom"),
             os.path.join(module_dir, ".gradle", "loom-cache"),
             os.path.join(module_dir, "build", "loom-cache")]
    found = []
    for root in roots:
        for j in glob.glob(os.path.join(root, "**", "*.jar"), recursive=True):
            base = os.path.basename(j)
            parent = os.path.basename(os.path.dirname(j))
            if not base.startswith("minecraft-") or "sources" in base or "server" in base:
                continue
            if version in base or parent == version:
                found.append(j)
    # the official client jar first: it holds every client and common class under Mojang's names
    found.sort(key=lambda p: (os.path.basename(p) != "minecraft-client.jar", p))
    return found


# ---- mixin sources -----------------------------------------------------------------------------

def strip_comments(src):
    """Java source without // and /* */ comments; string and char literals kept as they are."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c in "\"'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def mixin_sources(module_dir):
    """(mixin class, source path) for every mixin the module's *.mixins.json configs list."""
    out = []
    for cfg in glob.glob(os.path.join(module_dir, "src", "*", "resources", "*.mixins.json")):
        with open(cfg, encoding="utf-8") as f:
            data = json.load(f)
        pkg = data.get("package", "")
        for key in ("mixins", "client", "server"):
            for name in data.get(key) or []:
                fqcn = pkg + "." + name
                rel = fqcn.replace(".", "/") + ".java"
                hits = glob.glob(os.path.join(module_dir, "src", "*", "java", rel))
                out.append((fqcn, hits[0] if hits else None))
    return out


def balanced(src, start):
    """The text inside the parentheses that open at src[start]."""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
    return src[start + 1:]


def resolve(ref, imports):
    """A Java class reference (Foo, Foo.Bar, net.minecraft.x.Foo.Bar) as an internal name, or None."""
    parts = ref.split(".")
    if parts[0][:1].islower():   # fully qualified: package parts are lower case
        k = next((i for i, p in enumerate(parts) if p[:1].isupper()), None)
        if k is None:
            return None
        return "/".join(parts[:k]) + "/" + "$".join(parts[k:])
    base = imports.get(parts[0])
    if base is None:
        return None
    return base.replace(".", "/") + "".join("$" + p for p in parts[1:])


def mixin_targets(src, imports):
    m = re.search(r"@Mixin\s*\(", src)
    if not m:
        return []
    body = balanced(src, m.end() - 1)
    targets = []
    for ref in re.findall(r"([\w.]+)\.class", body):
        t = resolve(ref, imports)
        if t:
            targets.append(t)
    tm = re.search(r"targets\s*=\s*(\{[^}]*\}|\"[^\"]*\")", body)
    if tm:
        targets += [s.replace(".", "/") for s in re.findall(r"\"([^\"]+)\"", tm.group(1))]
    return targets


def method_name(spec):
    """The bare name from a method selector: "render", "render(F)V", "Lowner;render(F)V"."""
    s = spec.strip()
    if s.startswith("L") and ";" in s:
        s = s.split(";", 1)[1]
    return re.split(r"[(:]", s, maxsplit=1)[0]


def find_member(index, owner, name, field):
    """True if owner or a supertype has it; None when the chain leaves the jar (can't tell)."""
    seen = set()
    todo = [owner]
    unknown = False
    while todo:
        c = todo.pop()
        if c in seen or c is None:
            continue
        seen.add(c)
        if c == "java/lang/Object":   # every chain ends here: its own methods are known
            if not field and name in OBJECT_METHODS:
                return True
            continue
        info = index.get(c) if c.startswith(MC_PREFIXES) else None
        if info is None:
            unknown = True   # a JDK or library class (Enum, Record, a Guava type): not in Minecraft's jar
            continue
        if name in (info.fields if field else info.methods):
            return True
        todo.append(info.super)
        todo.extend(info.interfaces)
    return None if unknown else False


def check_source(path, index):
    """Problems found in one mixin source: a list of strings (empty = fine)."""
    with open(path, encoding="utf-8") as f:
        src = strip_comments(f.read())
    imports = {i.rsplit(".", 1)[1]: i for i in re.findall(r"^\s*import\s+([\w.]+)\s*;", src, re.M)}
    targets = [t for t in mixin_targets(src, imports) if t.startswith(MC_PREFIXES)]
    if not targets:
        return [], 0
    name = os.path.basename(path)
    problems = []
    infos = []
    for t in targets:
        info = index.get(t)
        if info is None:
            problems.append("%s: target class %s does not exist" % (name, t))
        else:
            infos.append(info)
    if not infos:
        return problems, 0
    checked = 0

    def declared(n, field):
        if "*" in n:
            rx = re.compile("^" + re.escape(n).replace("\\*", ".*") + "$")
            return any(rx.match(x) for i in infos for x in (i.fields if field else i.methods))
        return any(n in (i.fields if field else i.methods) for i in infos)

    for sel in re.findall(r"\bmethod\s*=\s*(\{[^}]*\}|\"[^\"]*\")", src):
        for spec in re.findall(r"\"([^\"]+)\"", sel):
            n = method_name(spec)
            if not n:
                continue
            checked += 1
            if not declared(n, False):
                problems.append("%s: method \"%s\" is not in %s" % (name, n, " / ".join(i.name for i in infos)))
    for owner, member, kind in re.findall(r"\btarget\s*=\s*\"L([\w/$]+);([\w$<>]+)([(:])", src):
        if not owner.startswith(MC_PREFIXES):
            continue
        checked += 1
        if index.get(owner) is None:
            problems.append("%s: call target class %s does not exist" % (name, owner))
        elif find_member(index, owner, member, kind == ":") is False:
            problems.append("%s: call target %s.%s does not exist" % (name, owner, member))
    for kind, args, decl in re.findall(r"@(Accessor|Invoker)\s*(\([^)]*\))?\s*[^;{]*?\b(\w+)\s*\(", src):
        explicit = re.search(r"\"([^\"]+)\"", args or "")
        if explicit:
            n = explicit.group(1)
        else:
            m = re.match(r"(?:get|set|is|call|invoke)([A-Z]\w*)$", decl)
            if not m:
                continue
            rest = m.group(1)
            # Mixin's own rule: getFOO -> FOO (a constant), getFooBar -> fooBar
            n = rest if rest.isupper() else rest[0].lower() + rest[1:]
        checked += 1
        if not declared(n, kind == "Accessor"):
            problems.append("%s: @%s \"%s\" is not in %s" % (name, kind, n, " / ".join(i.name for i in infos)))
    return problems, checked


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("module_dir")
    ap.add_argument("--jar", action="append", default=[])
    a = ap.parse_args(argv)
    version = minecraft_version(a.module_dir)
    jars = a.jar or find_jars(a.module_dir, version or "?")
    if not jars:
        print("::error::Minecraft %s jar not found in Loom's caches: this module's mixin targets are UNCHECKED." % version)
        return 1
    index = JarIndex(jars)
    if index.get("net/minecraft/client/Minecraft") is None:
        print("::error::No net/minecraft/client/Minecraft in %s: this module's mixin targets are UNCHECKED." % jars)
        return 1
    problems, mixins, names = [], 0, 0
    for fqcn, path in mixin_sources(a.module_dir):
        if path is None:
            problems.append("%s: listed in a mixins.json but has no source" % fqcn)
            continue
        p, n = check_source(path, index)
        problems += p
        names += n
        mixins += 1 if n or p else 0
    print("Checked %d Minecraft mixins (%d names) against %d classes in %s" % (
        mixins, names, len(index), ", ".join(os.path.basename(j) for j in jars)))
    if mixins == 0:
        print("::error::No Minecraft mixin was checked: the gate found nothing to check, which is never right.")
        return 1
    if problems:
        print("::error::Origin mixins target things that don't exist on Minecraft %s "
              "(the feature behind each would silently do nothing):" % version)
        for p in problems:
            print("  " + p)
        return 1
    print("No dead mixin targets.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
