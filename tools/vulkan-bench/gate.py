"""Which jars would Origin's Vulkan gate block (VulkanMode.BlockingIds via id / provides / depends / recommends)."""
import zipfile, json, os, sys, re
BL = {"sodium", "indium", "embeddium", "iris", "immediatelyfast", "voxy", "distanthorizons", "satin", "iceberg", "colorwheel"}
d = sys.argv[1]
for f in sorted(os.listdir(d)):
    if not f.endswith(".jar"):
        continue
    try:
        z = zipfile.ZipFile(os.path.join(d, f))
        raw = z.read("fabric.mod.json").decode("utf-8", "replace")
        j = json.loads(re.sub(r"[\x00-\x1f]", " ", raw), strict=False)
    except Exception as e:
        print("READFAIL", f, e)
        continue
    hits = []
    if j.get("id") in BL:
        hits.append("is:" + j["id"])
    for p in j.get("provides", []) or []:
        if p in BL:
            hits.append("provides:" + p)
    for k in ("depends", "recommends"):
        for dep in (j.get(k) or {}):
            if dep in BL:
                hits.append(k + ":" + dep)
    br = [x for x in (j.get("breaks") or {}) if x in BL or x == "vulkanmod"]
    if hits or br:
        print("BLOCK" if hits else "note ", f, j.get("id"), hits, "breaks:", br)
