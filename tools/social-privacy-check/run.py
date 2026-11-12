"""Where you are never reaches Origin's service while you appear offline (security audit F3).

    python tools/social-privacy-check/run.py

Every live module's social/Social.java + social/SocialClient.java are checked as source (they need
Minecraft and the network, so they can't run here). The rules, from docs/SOCIAL.md (Privacy):
- presence() asks shareWhereabouts() once and, when it says no, sends no server address and no
  party position / LAN world key (those are the only server fields a sync carries: syncBody reads
  them from the Presence it is handed).
- shareWhereabouts() is "Friends & Party on AND Appear Offline known to be off"; unknown (before
  the first sync) counts as hidden.
- the presence the syncs send comes only from presence(mc) (one setPresence call).
- party ping and warp (the two actions that carry a server) refuse first while you appear offline.
- no other request in SocialClient carries a "server" / "stateServer" field (a new one fails here
  until it is checked against the rule above).
CI: build-check.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def one(module, rel):
    found = glob.glob(os.path.join(ROOT, "src/mods/versions", module, "src/*/java/com/origin/client/client/social", rel))
    return open(found[0], encoding="utf-8").read().replace("\r\n", "\n") if len(found) == 1 else None


def method(src, head):
    """The text of the method starting with `head`, to its closing tab-brace."""
    i = src.find(head)
    if i < 0:
        return None
    j = src.find("\n\t}\n", i)
    return src[i:j] if j > 0 else None


def check(module):
    errs = []
    social, client = one(module, "Social.java"), one(module, "SocialClient.java")
    if social is None or client is None:
        return ["social/Social.java or SocialClient.java missing"]

    share = method(social, "static boolean shareWhereabouts()")
    if share is None or "return Mods.on(MOD) && SocialClient.knownVisible();" not in share:
        errs.append("shareWhereabouts() must be Mods.on(MOD) && SocialClient.knownVisible()")

    pres = method(social, "private static SocialClient.Presence presence(Minecraft mc)")
    if pres is None:
        errs.append("presence() not found")
    else:
        if "boolean share = shareWhereabouts();" not in pres:
            errs.append("presence() doesn't ask shareWhereabouts()")
        if re.search(r"(?<!share \? )currentServer\(mc\)", pres):
            errs.append("presence() reads currentServer(mc) without the share check")
        if re.search(r"(?<!share \? )partyState\(mc\)", pres):
            errs.append("presence() sends partyState(mc) without the share check")

    calls = re.findall(r"SocialClient\.setPresence\([^;]*;", social)
    if calls != ["SocialClient.setPresence(presence(mc));"]:
        errs.append(f"setPresence must be called once, with presence(mc): {calls}")

    visible = method(client, "public static boolean knownVisible()")
    if visible is None or "return me != null && !me.appearOffline();" not in visible or "return !p;" not in visible:
        errs.append("knownVisible() must be false until a sync (or the toggle) says Appear Offline is off")

    guard = "\t\tif (!knownVisible()) {\n\t\t\treturn CompletableFuture.completedFuture(HIDDEN);"
    for head in ("public static CompletableFuture<String> ping(", "public static CompletableFuture<String> warp("):
        body = method(client, head)
        if body is None or not body.split("{\n", 1)[1].startswith(guard[0:]):
            errs.append(f"{head.split('(')[0].split()[-1]}() must refuse first while you appear offline")

    # Every server field the client can send, by count: ping, warp, presence, partyState, stateServer.
    if client.count('addProperty("server"') != 4 or client.count('"stateServer"') != 1:
        errs.append('SocialClient sends a "server"/"stateServer" field this check does not know about')
    if 'addProperty("server", p.server())' not in client or 'addProperty("server", ps.server())' not in client \
            or 'addProperty("stateServer", here.server())' not in client:
        errs.append("syncBody's server fields must come from the Presence it is handed")
    return errs


def main():
    bad = 0
    modules = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    for m in modules:
        for e in check(m):
            print(f"FAIL {m}: {e}")
            bad += 1
    print(f"social privacy: {len(modules)} modules, {bad} failures")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
