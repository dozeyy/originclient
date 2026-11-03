"""Curation helper for the Resource Pack Hub's Picks (run in CI: the cloud
sandbox can't reach Modrinth or creator sites; GitHub runners can).

    python tools/packs/discover.py            # prints compact tables to stdout

Prints, for a person choosing picks:
  * Modrinth details of every candidate slug (owner, downloads, created, which
    of Origin's live game versions it really publishes files for, licence)
  * Modrinth searches (PvP/Bedwars/style queries, per era) - who else uploaded
    a pack under the same name (re-upload candidates for the copies blocklist)
  * status + download links of creator pages (direct-file and link-out picks)
Read-only: it downloads no pack files (verify_picks.py hashes the chosen ones).
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UA = "dozeyy/originclient pack-picks curation (will@willhenry.me)"
API = "https://api.modrinth.com/v2"


def live_versions():
    mods = json.load(open(os.path.join(ROOT, "src/mods/live-modules.json"), encoding="utf-8"))["modules"]
    out = []
    for m in mods:
        out += m["versions"]
    return out


def get(url, raw=False, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read(4 << 20)
        return (r.status, r.geturl(), body) if raw else json.loads(body)


def api(path):
    time.sleep(0.25)  # well under Modrinth's 300 requests/minute
    return get(API + path)


CANDIDATES = [
    "looshy", "betterbedwars", "pxrple-16x", "ratio", "apex-pvp", "huh-8x", "faithful-32x", "faithful-64x",
    "low-on-fire", "simple-short-swords", "short-swords", "low-fire-pack", "default-dark-mode", "bare-bones",
    "crystal-pvp-lt3-essentials", "bluxycrystal", "crystal-pvp-tweaks", "great-bedwars-16x", "bedwarstp",
    "vertexvirtual", "stay-true", "xalis-enchanted-books", "clear-glass", "faithful-x32", "visible-ores",
]
QUERIES = ["bedwars", "pvp", "bombies", "prism", "looshy", "atlas pvp", "azura", "luminous", "vaporwave",
           "dewier", "nicofruit", "faithful", "short swords", "low fire", "crystal pvp", "fps boost", "default edit",
           "dark mode", "16x pvp", "hypixel", "skywars", "minimal", "clean pvp", "8x pvp", "purple pvp",
           "blue pvp", "red pvp", "anime pvp", "kawaii", "ranked bedwars", "tory", "bloom", "cubed", "doggo"]
ERAS = ["1.8.9", "1.16.5", "1.20.1", "1.21.1", "1.21.11", "26.1.2", "26.3"]
PAGES = [
    "https://faithfulpack.net/faithful32x", "https://faithfulpack.net/faithful32x/Java",
    "https://faithfulpack.net/faithful32x/R5", "https://faithfulpack.net/downloads",
    "https://faithfulpack.net/faithful64x", "https://faithfulpack.net/faithful32x/1.8.9",
    "https://vanillatweaks.net/picker/resource-packs/", "https://pvprp.com/pack?p=9566",
    "https://pvprp.com/pack?p=345", "https://pvprp.com/pack?p=237", "https://pvprp.com/pack?p=1769",
    "https://pvprp.com/", "https://pvprp.com/search?q=prism", "https://pvprp.com/search?q=ranked+bedwars",
    "https://pvprp.com/search?q=azura", "https://pvprp.com/search?q=luminous", "https://pvprp.com/search?q=vaporwave",
    "https://pvprp.com/search?q=bedwars", "https://pvprp.com/packs?sort=downloads",
    "https://www.curseforge.com/minecraft/texture-packs/bedwars",
    "https://www.curseforge.com/minecraft/texture-packs/better-bedwars",
    "https://github.com/Faithful-Resource-Pack/Faithful-32x-Java/releases",
    "https://api.github.com/repos/Faithful-Resource-Pack/Faithful-32x-Java/releases?per_page=1",
    "https://api.github.com/repos/Faithful-Resource-Pack/Faithful-64x-Java/releases?per_page=3",
]


def span(vs, live):
    have = [v for v in live if v in vs]
    return ",".join(have) if len(have) <= 8 else f"{len(have)} live: {have[-1]}..{have[0]}"


def project_line(slug, live):
    try:
        p = api("/project/" + urllib.parse.quote(slug))
    except Exception as e:  # noqa: BLE001 - report and move on
        return f"  {slug:<28} ERROR {e}"
    try:
        members = api(f"/project/{p['id']}/members")
        owners = [m["user"]["username"] for m in members if m.get("role", "").lower() == "owner" or m.get("is_owner")]
        team = [m["user"]["username"] for m in members]
    except Exception:  # noqa: BLE001
        owners, team = ["?"], []
    org = p.get("organization") or ""
    vers = api(f"/project/{p['id']}/version")
    gv = set()
    for v in vers:
        if "minecraft" in v.get("loaders", []) or not v.get("loaders"):
            gv.update(v["game_versions"])
    icon = p.get("icon_url") or ""
    gal = [g["url"] for g in p.get("gallery", []) if g.get("featured")] or [g["url"] for g in p.get("gallery", [])][:1]
    return (f"  {slug:<28} id={p['id']} '{p['title']}' owner={owners} team={team} org={org} dl={p['downloads']} "
            f"created={p['published'][:10]} lic={p['license']['id']} cats={p['categories']}+{p.get('additional_categories', [])}\n"
            f"      live={span(gv, live)} | 1.8.9={'1.8.9' in gv} 1.8.x={[g for g in gv if g.startswith('1.8')]}\n"
            f"      icon={icon}\n      gallery={gal[0] if gal else ''}\n      desc={p['description'][:140]!r}")


def search(query, version=None, combat=False, limit=25, index="relevance"):
    facets = [["project_type:resourcepack"]]
    if version:
        facets.append([f"versions:{version}"])
    if combat:
        facets.append(["categories:combat"])
    q = f"/search?limit={limit}&index={index}&facets=" + urllib.parse.quote(json.dumps(facets))
    if query:
        q += "&query=" + urllib.parse.quote(query)
    return api(q)


def hit_line(h):
    return (f"    {h['project_id']} {h['slug'][:30]:<30} {h['title'][:34]!r:<36} by {h['author'][:18]:<18} "
            f"dl={h['downloads']:<9} c={h['date_created'][:10]} v={(h['versions'] or ['?'])[0]}..{(h['versions'] or ['?'])[-1]}"
            f" d={h['description'][:50]!r}")


def page(url):
    try:
        st, final, body = get(url, raw=True)
    except Exception as e:  # noqa: BLE001
        return f"  {url}\n    -> ERROR {str(e)[:120]}"
    text = body.decode("utf-8", "replace")
    out = [f"  {url}\n    -> {st} {final} {len(body)} bytes"]
    if "api.github.com" in url:
        try:
            for r in json.loads(text)[:60]:
                if "assets" in r:
                    out.append(f"    release {r['tag_name']} {r['published_at'][:10]}: " +
                               "; ".join(f"{a['name']} {a['size']} {a['browser_download_url']}" for a in r["assets"]))
                else:
                    out.append(f"    branch {r.get('name')}")
        except Exception:  # noqa: BLE001
            pass
        return "\n".join(out)
    links = sorted(set(re.findall(r'href="([^"]+)"', text)))
    keep = [l for l in links if re.search(r"(\.zip|download|/pack\?p=|mediafire|forgecdn|github\.com/.+/(releases|archive)|r5|1\.8)", l, re.I)]
    for l in keep[:40]:
        out.append(f"    link {l}")
    title = re.search(r"<title>([^<]*)</title>", text, re.I)
    if title:
        out.append(f"    title {title.group(1).strip()[:100]!r}")
    return "\n".join(out)


def main():
    live = live_versions()
    print("== live game versions:", live)
    print("== candidates")
    for s in CANDIDATES:
        print(project_line(s, live), flush=True)
    print("== queries (any version, relevance)")
    for q in QUERIES:
        try:
            r = search(q, limit=12)
        except Exception as e:  # noqa: BLE001
            print(f"  [{q}] ERROR {e}")
            continue
        print(f"  [{q}] total={r['total_hits']}")
        for h in r["hits"]:
            print(hit_line(h))
    print("== per era: combat by downloads, then 'bedwars' and 'pvp' by downloads")
    for v in ERAS:
        for (q, combat) in (("", True), ("bedwars", False), ("pvp", False)):
            try:
                r = search(q, version=v, combat=combat, limit=20, index="downloads")
            except Exception as e:  # noqa: BLE001
                print(f"  [{v} {q or 'combat'}] ERROR {e}")
                continue
            print(f"  [{v} {q or 'combat'}] total={r['total_hits']}")
            for h in r["hits"]:
                print(hit_line(h))
    print("== pages")
    for u in PAGES:
        print(page(u), flush=True)


if __name__ == "__main__":
    sys.exit(main())
