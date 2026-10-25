# -*- coding: utf-8 -*-
"""اختبار على كمبيوتر: يقرأ fixture.zip (بصيغة تصدير تليجرام ديسكتوب) ويبني PPTX بنفس الكود اللي هيشتغل في التطبيق."""
import json, os, sys, tempfile, zipfile, re
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
import munsif_core as core, pptx_writer

IMAGE_MIMES = re.compile(r"^image/(jpeg|jpg|png|gif|webp|bmp)$", re.I)

def segs(text):
    out = []
    if isinstance(text, str):
        if text: out.append((text, "plain"))
    elif isinstance(text, list):
        for it in text:
            if isinstance(it, str):
                if it: out.append((it, "plain"))
            elif isinstance(it, dict) and it.get("text"):
                out.append((str(it["text"]), it.get("type") or "plain"))
    return out

def load_export(zip_path, workdir):
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(workdir)
    res = None
    for root, _, files in os.walk(workdir):
        if "result.json" in files: res = os.path.join(root, "result.json")
    base = os.path.dirname(res)
    data = json.load(open(res, encoding="utf-8-sig"))
    msgs = []
    def rf(rel):
        if not isinstance(rel, str) or not rel or rel[0] == "(": return None
        p = os.path.join(base, rel)
        return p if os.path.exists(p) else None
    for m in data["messages"]:
        mime = m.get("mime_type") if isinstance(m.get("mime_type"), str) else ""
        f = rf(m.get("file"))
        msgs.append({"type": m.get("type"), "sender": str(m.get("from_id", m.get("from", ""))),
            "ts": int(m.get("date_unixtime", 0)), "segments": segs(m.get("text")),
            "media_type": m.get("media_type"), "photo": rf(m.get("photo")), "thumbnail": rf(m.get("thumbnail")),
            "file": f, "file_name": m.get("file_name") or (os.path.basename(m["file"]) if isinstance(m.get("file"), str) else None),
            "file_is_image": bool(f and IMAGE_MIMES.match(mime))})
    return data.get("name", "chat"), msgs

if __name__ == "__main__":
    zp, out = sys.argv[1], sys.argv[2]
    gap = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    d = tempfile.mkdtemp()
    name, msgs = load_export(zp, d)
    groups, stats = core.build_groups(msgs, gap)
    groups = core.split_oversized(groups)
    keys = {p: core.hash_file(p) for p in core.image_paths_of(groups)}
    groups, removed = core.dedupe_groups(groups, keys)
    skipped = pptx_writer.build_pptx(groups, out, title=name)
    print(json.dumps({"name": name, "stats": stats, "slides": len(groups), "removed": removed, "skipped": skipped}, ensure_ascii=False))
