# -*- coding: utf-8 -*-
"""المنصف في القدرات — منطق التنظيف والتجميع (نفس منطق نسخة الويب/الكمبيوتر).

الرسالة (message) هنا قاموس بالشكل ده:
    {
      "type": "message",
      "sender": "123",            # مفتاح المرسل (مش بيتعرض أبدًا)
      "ts": 1760000000,           # ثواني
      "segments": [("نص", "plain"), ("غامق", "bold")],
      "media_type": None | "sticker" | "voice_message" | ...,
      "photo": None | "مسار صورة",
      "file": None | "مسار ملف",
      "file_name": None | "اسم",
      "file_is_image": False,
    }
"""
import hashlib
import re

IMAGE_WEIGHT = 350
MAX_WEIGHT_PER_SLIDE = 1400

NEW_TOPIC_RE = re.compile(
    r"^\s*(?:[\d٠-٩]{1,3}\s*[.)\-–:]"
    r"|(?:ال)?س[ؤٔو]ال\s*[\d٠-٩]*\s*[:\-]?"
    r"|[Qq]\s*\d*\s*[:\-])"
)
HANDLE_RE = re.compile(r"\(\s*@[A-Za-z0-9_]{3,32}\s*\)|@[A-Za-z0-9_]{3,32}")
ARABIC_CHAR_RE = re.compile(r"[؀-ۿ]")
LATIN_CHAR_RE = re.compile(r"[A-Za-z]")

MEDIA_LABELS = {
    "sticker": "[ملصق]",
    "voice_message": "[رسالة صوتية]",
    "video_message": "[فيديو دائري]",
    "animation": "[GIF متحرك]",
    "video_file": "[فيديو]",
    "audio_file": "[ملف صوتي]",
}


# ---------- تطبيع عربي للمطابقة فقط ----------
_TASHKEEL_RE = re.compile(r"[ً-ٰـ]")


def norm_ar(s):
    s = _TASHKEEL_RE.sub("", s)
    s = re.sub(r"[أإآ]", "ا", s)
    s = s.replace("ى", "ي").replace("ة", "ه")
    return s


def is_stamp_line(line):
    n = norm_ar(line)
    if "الرساله موجهه من حساب" in n:
        return True
    if re.match(r"^[\s\-–—•*]*بواسطه\s*[:：]", n):
        return True
    if re.search("\U0001F194\\s*[\\d٠-٩]+", line):
        return True
    if re.match("^\\s*\U0001F4E8", line):
        return True
    return False


def _stamp_ranges(full):
    ranges = []
    pos = 0
    for line in full.split("\n"):
        start = pos
        end = pos + len(line)
        if is_stamp_line(line):
            ranges.append((start, min(end + 1, len(full))))
        pos = end + 1
    return ranges


def strip_handles(text):
    text = HANDLE_RE.sub("", text)
    return re.sub(r"[ \t]{2,}", " ", text)


def clean_segments(segments):
    """[(text,type)] -> [{'text','bold','italic'}] بعد مسح الأختام والـ @handles."""
    segs = [(str(t).replace("\r\n", "\n").replace("\r", "\n"), ty) for t, ty in segments]
    full = "".join(t for t, _ in segs)
    ranges = _stamp_ranges(full)
    runs = []
    offset = 0
    for text, ty in segs:
        start = offset
        end = offset + len(text)
        offset = end
        kept = ""
        cur = start
        for ra0, rb0 in ranges:
            if rb0 <= start or ra0 >= end:
                continue
            ra = max(ra0, start)
            rb = min(rb0, end)
            kept += full[cur:ra]
            cur = rb
        kept += full[cur:end]
        kept = strip_handles(kept)
        if kept.strip() == "":
            if "\n" in kept and runs:
                runs.append({"text": "\n", "bold": False, "italic": False})
            continue
        runs.append({"text": kept, "bold": ty == "bold", "italic": ty == "italic"})
    while runs and runs[0]["text"].strip() == "":
        runs.pop(0)
    while runs and runs[-1]["text"].strip() == "":
        runs.pop()
    if runs:
        runs[0]["text"] = runs[0]["text"].lstrip()
        runs[-1]["text"] = runs[-1]["text"].rstrip()
    return [r for r in runs if r["text"] != ""]


def plain_of(runs):
    return "".join(r["text"] for r in runs)


# ---------- التجميع ----------
def _display_image(msg):
    if msg.get("photo"):
        return msg["photo"]
    if msg.get("file_is_image") and msg.get("file"):
        return msg["file"]
    if msg.get("thumbnail"):
        return msg["thumbnail"]
    return None


def _media_note(msg, image):
    mt = msg.get("media_type")
    if mt and mt in MEDIA_LABELS and not image:
        return MEDIA_LABELS[mt]
    if msg.get("file") is not None and not msg.get("photo") and not mt and not image:
        return "[ملف مرفق: %s]" % (msg.get("file_name") or "")
    return None


def build_groups(messages, gap_seconds, from_ts=None, to_ts=None):
    groups = []
    current = None
    prev_time = None
    prev_sender = None
    stats = {"messages": 0, "kept": 0, "stamps": 0}
    for msg in messages:
        if msg.get("type") != "message":
            continue
        ts = msg.get("ts", 0)
        if from_ts is not None and ts < from_ts:
            continue
        if to_ts is not None and ts >= to_ts:
            continue
        stats["messages"] += 1
        runs = clean_segments(msg.get("segments") or [])
        text = plain_of(runs).strip()
        raw_text = "".join(t for t, _ in (msg.get("segments") or [])).strip()
        image = _display_image(msg)
        note = _media_note(msg, image)
        if not text and not note and not image:
            if raw_text:
                stats["stamps"] += 1
            continue
        starts_new = bool(text) and bool(NEW_TOPIC_RE.match(text))
        same_sender = msg.get("sender") == prev_sender
        within_gap = prev_time is not None and (ts - prev_time) <= gap_seconds
        item = {"runs": runs, "note": note, "image": image}
        if current is not None and same_sender and within_gap and not (starts_new and current["items"]):
            current["items"].append(item)
        else:
            current = {"items": [item]}
            groups.append(current)
        prev_time = ts
        prev_sender = msg.get("sender")
        stats["kept"] += 1
    return groups, stats


def _item_weight(item):
    w = sum(len(r["text"]) for r in item["runs"])
    if item["note"]:
        w += len(item["note"])
    if item["image"]:
        w += IMAGE_WEIGHT
    return w


def split_oversized(groups, max_weight=MAX_WEIGHT_PER_SLIDE):
    out = []
    for g in groups:
        if sum(_item_weight(i) for i in g["items"]) <= max_weight:
            out.append(g)
            continue
        chunk = {"items": []}
        cw = 0
        for it in g["items"]:
            w = _item_weight(it)
            if chunk["items"] and cw + w > max_weight:
                out.append(chunk)
                chunk = {"items": []}
                cw = 0
            chunk["items"].append(it)
            cw += w
        if chunk["items"]:
            out.append(chunk)
    return out


def image_paths_of(groups):
    seen = set()
    out = []
    for g in groups:
        for it in g["items"]:
            p = it["image"]
            if p and p not in seen:
                seen.add(p)
                out.append(p)
    return out


def hash_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dedupe_groups(groups, image_keys):
    seen = set()
    out = []
    removed = 0
    for g in groups:
        parts = []
        for it in g["items"]:
            t = plain_of(it["runs"]) + (it["note"] or "")
            if it["image"]:
                t += "|IMG:" + (image_keys.get(it["image"]) or it["image"])
            parts.append(t)
        sig = re.sub(r"\s+", " ", "\u0001".join(parts)).strip()
        if not sig:
            out.append(g)
            continue
        if sig in seen:
            removed += 1
            continue
        seen.add(sig)
        out.append(g)
    return out, removed


# ---------- تقدير حجم الخط ----------
def arabic_ratio(text):
    ar = len(ARABIC_CHAR_RE.findall(text))
    la = len(LATIN_CHAR_RE.findall(text))
    if ar + la == 0:
        return 0.0
    return ar / (ar + la)


def _estimate_height_pt(paragraphs, width_pt, font_pt, space_after_pt):
    lines = 0
    for p in paragraphs:
        for line in p.split("\n"):
            per_line = max(1, int(width_pt // (font_pt * 0.55)))
            lines += max(1, -(-len(line) // per_line))
    return lines * font_pt * 1.3 + len(paragraphs) * space_after_pt


def pick_font_size(paragraphs, width_in, height_in):
    sizes = [32, 28, 24, 22, 20, 18, 16, 14, 12]
    width_pt = width_in * 72 - 12
    height_pt = height_in * 72 * 0.94
    for s in sizes:
        if _estimate_height_pt(paragraphs, width_pt, s, 8) <= height_pt:
            return s
    return sizes[-1]


def layout_image_row(sizes, max_w, max_h, gap):
    n = len(sizes)
    usable = max_w - gap * (n - 1)
    row_h = max_h
    total_w = sum((w / h) * row_h for w, h in sizes)
    if total_w > usable:
        row_h *= usable / total_w
    return row_h, [(w / h) * row_h for w, h in sizes]


# ---------- قياس الصور بدون مكتبات ----------
def sniff_image(b):
    if len(b) > 3 and b[0] == 0xFF and b[1] == 0xD8:
        return "jpeg"
    if len(b) > 8 and b[:4] == b"\x89PNG":
        return "png"
    if len(b) > 6 and b[:3] == b"GIF":
        return "gif"
    return None


def image_size(b, kind):
    if kind == "png":
        return int.from_bytes(b[16:20], "big"), int.from_bytes(b[20:24], "big")
    if kind == "gif":
        return b[6] | (b[7] << 8), b[8] | (b[9] << 8)
    if kind == "jpeg":
        i = 2
        n = len(b)
        while i + 9 < n:
            if b[i] != 0xFF:
                i += 1
                continue
            marker = b[i + 1]
            if marker == 0xFF:
                i += 1
                continue
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                return (b[i + 7] << 8) | b[i + 8], (b[i + 5] << 8) | b[i + 6]
            i += 2 + ((b[i + 2] << 8) | b[i + 3])
    return None


def load_image(path):
    """يرجع (bytes, ext, w, h) أو None لو الصيغة مش مدعومة."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    kind = sniff_image(data)
    if not kind:
        return None
    size = image_size(data, kind)
    if not size or size[0] <= 0 or size[1] <= 0:
        return None
    return data, ("jpeg" if kind == "jpeg" else kind), size[0], size[1]
