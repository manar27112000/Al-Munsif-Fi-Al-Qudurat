# -*- coding: utf-8 -*-
"""سحب الرسائل والصور من تليجرام (Telethon) وتحويلها لنفس صيغة munsif_core."""
import asyncio
import os
from datetime import datetime, timezone

from telethon import types

IMAGE_DOC_MIMES = ("image/jpeg", "image/jpg", "image/png", "image/gif")


class Cancelled(Exception):
    pass


def _ext_for(mime):
    return {"image/png": ".png", "image/gif": ".gif"}.get(mime, ".jpg")


def classify(m):
    """يرجع (media_type, is_image_doc, file_name, has_file)."""
    if isinstance(m.media, types.MessageMediaPhoto) and m.photo:
        return None, False, None, False
    if m.sticker:
        return "sticker", False, None, False
    if m.voice:
        return "voice_message", False, None, False
    if m.video_note:
        return "video_message", False, None, False
    if m.gif:
        return "animation", False, None, False
    if m.video:
        return "video_file", False, None, False
    if m.audio:
        return "audio_file", False, None, False
    if m.document:
        mime = (m.file.mime_type or "").lower() if m.file else ""
        name = (m.file.name if m.file else None) or "file"
        return None, mime in IMAGE_DOC_MIMES, name, True
    return None, False, None, False


async def fetch_chat(client, chat_id, from_ts, to_ts, workdir, progress, is_cancelled, parallel=4):
    entity = await client.get_input_entity(chat_id)
    offset = datetime.fromtimestamp(to_ts, tz=timezone.utc) if to_ts else None

    raw = []
    progress("fetch", 0, 0)
    async for m in client.iter_messages(entity, offset_date=offset):
        if is_cancelled():
            raise Cancelled()
        if from_ts and m.date.timestamp() < from_ts:
            break
        if isinstance(m, types.MessageService):
            continue
        raw.append(m)
        if len(raw) % 50 == 0:
            progress("fetch", len(raw), 0)
    raw.reverse()
    progress("fetch", len(raw), len(raw))

    os.makedirs(workdir, exist_ok=True)
    out = []
    jobs = []  # (index, message, path)
    for m in raw:
        media_type, is_image_doc, fname, has_file = classify(m)
        sender = m.sender_id if m.sender_id is not None else m.chat_id
        text = m.raw_text or ""
        msg = {
            "type": "message",
            "sender": str(sender),
            "ts": int(m.date.timestamp()),
            "segments": [(text, "plain")] if text else [],
            "media_type": media_type,
            "photo": None,
            "thumbnail": None,
            "file": None,
            "file_name": fname,
            "file_is_image": False,
        }
        if isinstance(m.media, types.MessageMediaPhoto) and m.photo:
            path = os.path.join(workdir, "p%d.jpg" % m.id)
            jobs.append((msg, "photo", m, path))
        elif is_image_doc:
            path = os.path.join(workdir, "d%d%s" % (m.id, _ext_for((m.file.mime_type or "").lower())))
            jobs.append((msg, "file", m, path))
            msg["file_is_image"] = True
        elif has_file:
            msg["file"] = "(not downloaded)"  # علشان تظهر ملاحظة [ملف مرفق: اسم]
        out.append(msg)

    total = len(jobs)
    done = 0
    progress("download", 0, total)
    sem = asyncio.Semaphore(parallel)

    async def one(job):
        nonlocal done
        msg, key, m, path = job
        async with sem:
            if is_cancelled():
                raise Cancelled()
            try:
                got = await client.download_media(m, file=path)
            except Cancelled:
                raise
            except Exception:
                got = None
            if got and os.path.exists(got) and os.path.getsize(got) > 0:
                msg[key] = got
        done += 1
        progress("download", done, total)

    if jobs:
        await asyncio.gather(*[one(j) for j in jobs])
    return out
