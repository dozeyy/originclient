#!/usr/bin/env python3
"""Static checks for originclient.org (website/), run by .github/workflows/website-check.yml on every
push that touches the site, and before the GitHub Pages copy is published.

Vercel serves the real site straight from main (vercel.json), so these checks are the guard
that fails red on a bad push; vercel.json's ignoreCommand additionally refuses to deploy a
promos.json that doesn't parse.

  python3 tools/website/check_site.py        -> prints every problem, exit 1 if any
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parents[2]
SITE = REPO / "website"
ORIGIN = "https://originclient.org"
# Page file -> its clean address (vercel.json cleanUrls).
PAGES = {"index.html": "/", "store.html": "/store", "terms.html": "/terms", "privacy.html": "/privacy"}

# Mirrors src/OriginLauncher.App/Core/Promos/PromoFeed.cs (PromoFeedParser). The parser drops
# what it can't use instead of failing; here anything it would drop is an error, so a typo
# never silently removes a banner card from every launcher.
MAX_PROMOS, MAX_LABEL, MAX_TITLE, MAX_SUBTITLE = 12, 18, 64, 96
KINDS = {"link", "discord", "tips"}


def is_https(url: object) -> bool:
    if not isinstance(url, str):
        return False
    u = urlparse(url.strip())
    return u.scheme == "https" and bool(u.hostname)


def promo_feed_problems(text: str) -> list[str]:
    """Everything wrong with a launcher/promos.json text (empty list = good)."""
    try:
        feed = json.loads(text)
    except ValueError as e:
        return [f"promos.json is not valid JSON: {e}"]
    if not isinstance(feed, dict):
        return ["promos.json must be a JSON object"]
    out: list[str] = []
    if feed.get("schema") != 1 or isinstance(feed.get("schema"), bool):
        out.append("promos.json: schema must be the number 1 (launchers reject any other schema whole)")
    promos = feed.get("promos")
    if not isinstance(promos, list) or not promos:
        out.append("promos.json: promos must be a non-empty list")
        promos = []
    if len(promos) > MAX_PROMOS:
        out.append(f"promos.json: at most {MAX_PROMOS} promos are shown")
    ids: set[str] = set()
    for i, p in enumerate(promos):
        where = f"promos.json promos[{i}]"
        if not isinstance(p, dict):
            out.append(f"{where}: must be an object")
            continue
        kind = str(p.get("kind", "link")).strip().lower()
        if kind not in KINDS:
            out.append(f"{where}: kind must be one of {sorted(KINDS)}")
        pid = p.get("id", f"promo-{i}")
        if pid in ids:
            out.append(f"{where}: duplicate id {pid!r} (the launcher keeps only the first)")
        ids.add(pid)
        if len(str(p.get("label", ""))) > MAX_LABEL:
            out.append(f"{where}: label longer than {MAX_LABEL} characters is cut")
        if kind == "link":
            title = p.get("title")
            if not isinstance(title, str) or not title.strip():
                out.append(f"{where}: a link promo needs a title (the launcher drops it otherwise)")
            elif len(title) > MAX_TITLE:
                out.append(f"{where}: title longer than {MAX_TITLE} characters is cut")
            if len(str(p.get("subtitle", ""))) > MAX_SUBTITLE:
                out.append(f"{where}: subtitle longer than {MAX_SUBTITLE} characters is cut")
            for key in ("clickUrl", "imageUrl", "previewUrl"):
                if key in p and not is_https(p[key]):
                    out.append(f"{where}: {key} must be an https URL (the launcher ignores anything else)")
    discord = feed.get("discord")
    if isinstance(discord, dict):
        code = discord.get("inviteCode")
        if not isinstance(code, str) or not code.strip() or code == "REPLACE_ME":
            out.append("promos.json: discord.inviteCode must be the real invite code")
    network = feed.get("network")
    if isinstance(network, dict) and "url" in network and not is_https(network["url"]):
        out.append("promos.json: network.url must be an https URL")
    return out


def vercel_problems() -> list[str]:
    out: list[str] = []
    site_cfg, root_cfg = SITE / "vercel.json", REPO / "vercel.json"
    # The Vercel project's Root Directory setting isn't recorded (MEMORY 2026-10-18), so both
    # copies exist and must say the same thing.
    if site_cfg.read_text(encoding="utf-8") != root_cfg.read_text(encoding="utf-8"):
        out.append("vercel.json and website/vercel.json differ: keep them identical")
    cfg = json.loads(site_cfg.read_text(encoding="utf-8"))
    # Vercel rejects the whole config (every deploy fails in about a second, the site silently stays
    # on the last good build) when ignoreCommand is longer than 256 characters: that froze the live
    # site from v1.0.87 to 2026-11-04.
    ignore = cfg.get("ignoreCommand", "")
    if len(ignore) > 256:
        out.append(f"vercel.json ignoreCommand is {len(ignore)} characters; Vercel allows at most 256 (every deploy fails)")
    csp = ""
    for rule in cfg.get("headers", []):
        for h in rule.get("headers", []):
            if h.get("key") == "Content-Security-Policy":
                csp = h.get("value", "")
    if not csp:
        return out + ["vercel.json: no Content-Security-Policy header"]
    connect = next((d.split()[1:] for d in csp.split(";") if d.strip().startswith("connect-src")), [])
    api = json.loads((SITE / "cosmetics" / "service.json").read_text(encoding="utf-8")).get("api", "")
    origin = "{0.scheme}://{0.netloc}".format(urlparse(api))
    if api and origin not in connect:
        out.append(f"vercel.json CSP connect-src lacks the store service {origin} (website/cosmetics/service.json): "
                   "the Store could not load prices or check out")
    # Hosts the pages' own scripts call: fetch('https://host...') and the GitHub releases API.
    js = "\n".join(f.read_text(encoding="utf-8") for f in (SITE / "js").glob("*.js") if f.is_file())
    hosts = set(re.findall(r"fetch\(\s*[`'\"]https://([^/`'\"$]+)", js))
    if "https://api.github.com/" in js:
        hosts.add("api.github.com")
    for host in sorted(hosts):
        if f"https://{host}" not in connect:
            out.append(f"vercel.json CSP connect-src lacks https://{host}, which website/js calls")
    return out


def page_problems() -> list[str]:
    out: list[str] = []
    sitemap = (SITE / "sitemap.xml").read_text(encoding="utf-8")
    for page, path in PAGES.items():
        html = (SITE / page).read_text(encoding="utf-8")
        url = ORIGIN + path
        if f'<link rel="canonical" href="{url}">' not in html:
            out.append(f"{page}: needs <link rel=\"canonical\" href=\"{url}\">")
        m = re.search(r'<meta property="og:image" content="https://originclient\.org/([^"]+)">', html)
        if not m:
            out.append(f"{page}: needs an og:image on originclient.org")
        elif not (SITE / m.group(1)).is_file():
            out.append(f"{page}: og:image {m.group(1)} doesn't exist in website/")
        if f"<loc>{url}</loc>" not in sitemap:
            out.append(f"sitemap.xml: missing {url}")
    return out


def copy_problems() -> list[str]:
    """Claims Will ruled on (DECISIONS.md, Website) and copies that must stay in step."""
    out: list[str] = []
    files = [*SITE.glob("*.html"), *SITE.glob("ad/*.html"), *SITE.glob("js/*.js"), SITE / "launcher" / "promos.json"]
    for f in files:
        if re.search(r"every\s+(minecraft\s+)?version", f.read_text(encoding="utf-8"), re.I):
            out.append(f"{f.relative_to(REPO)}: never claim \"every version\" (DECISIONS.md, 2026-10-13)")
    site_js = (SITE / "js" / "site.js").read_text(encoding="utf-8")
    index = (SITE / "index.html").read_text(encoding="utf-8")
    groups = re.search(r"const VERSION_GROUPS = \[(.*?)\n\];", site_js, re.S)
    count = len(re.findall(r"'[0-9][0-9.]*'", re.sub(r"title: '[^']*',", "", groups.group(1)))) if groups else 0
    for shown in re.findall(r"data-version-count>(\d+)<", index):
        if int(shown) != count:
            out.append(f"index.html says {shown} versions but site.js VERSION_GROUPS lists {count}")
    if f"one of {count} versions" not in index:
        out.append(f"index.html meta description must say \"one of {count} versions\"")
    # The <noscript> download list repeats site.js's files for visitors without JavaScript.
    for name in re.findall(r"releases/latest/download/([^\"']+)", index):
        if name not in site_js:
            out.append(f"index.html links {name}, which site.js doesn't offer")
    # A "N reasons." heading must count its cards (only while the page has that card layout).
    bento = len(re.findall(r'<li class="bcard ', index))
    words = {4: "Four", 5: "Five", 6: "Six", 7: "Seven"}
    if bento and f"<h2>{words.get(bento, bento)} reasons." not in index:
        out.append(f"index.html: the Why heading must count the {bento} cards")
    return out


def main() -> int:
    problems = (promo_feed_problems((SITE / "launcher" / "promos.json").read_text(encoding="utf-8"))
                + vercel_problems() + page_problems() + copy_problems())
    for p in problems:
        print("FAIL:", p)
    if not problems:
        print("website checks: ok")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
