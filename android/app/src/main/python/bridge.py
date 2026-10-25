# -*- coding: utf-8 -*-
"""الواجهة اللي بتنادي عليها جافا (MainActivity). كل الدوال بترجع JSON نصي."""
import asyncio
import json
import os
import re
import shutil
import threading
import time
import traceback

import munsif_core as core
import pptx_writer

_loop = None
_thread = None
_client = None
_state = {"phone": None, "hash": None, "dir": None}
_progress = {"phase": "idle", "done": 0, "total": 0, "text": ""}
_cancel = {"flag": False}


def _ensure_loop():
    global _loop, _thread
    if _loop is None:
        _loop = asyncio.new_event_loop()
        _thread = threading.Thread(target=_loop.run_forever, daemon=True)
        _thread.start()
    return _loop


def _run(coro, timeout=None):
    fut = asyncio.run_coroutine_threadsafe(coro, _ensure_loop())
    return fut.result(timeout)


def _ok(**kw):
    d = {"ok": True}
    d.update(kw)
    return json.dumps(d, ensure_ascii=False)


def _err(code, detail=""):
    return json.dumps({"ok": False, "error": code, "detail": str(detail)[:300]}, ensure_ascii=False)


def _log_exc(session_dir):
    try:
        with open(os.path.join(session_dir or ".", "last_error.txt"), "w", encoding="utf-8") as f:
            f.write(traceback.format_exc())
    except Exception:
        pass


def _map_error(e):
    from telethon import errors
    name = type(e).__name__
    if isinstance(e, errors.ApiIdInvalidError):
        return "bad_credentials"
    if isinstance(e, (errors.PhoneNumberInvalidError, errors.PhoneNumberBannedError)):
        return "invalid_phone"
    if isinstance(e, errors.PhoneCodeInvalidError):
        return "code_invalid"
    if isinstance(e, (errors.PhoneCodeExpiredError, errors.PhoneCodeEmptyError)):
        return "code_expired"
    if isinstance(e, errors.PasswordHashInvalidError):
        return "password_invalid"
    if isinstance(e, errors.FloodWaitError):
        return "flood:%d" % getattr(e, "seconds", 0)
    if isinstance(e, (ConnectionError, OSError, asyncio.TimeoutError, TimeoutError)):
        return "network"
    return "unknown:" + name


async def _init(api_id, api_hash, session_dir):
    global _client
    from telethon import TelegramClient
    if _client is not None:
        try:
            await _client.disconnect()
        except Exception:
            pass
    os.makedirs(session_dir, exist_ok=True)
    _client = TelegramClient(
        os.path.join(session_dir, "munsif"), int(api_id), api_hash,
        device_model="Android", system_version="Android", app_version="1.0",
        lang_code="ar", system_lang_code="ar", connection_retries=3, request_retries=3,
        timeout=20,
    )
    await _client.connect()
    return await _client.is_user_authorized()


def init(api_id, api_hash, session_dir):
    _state["dir"] = session_dir
    try:
        authorized = _run(_init(api_id, api_hash, session_dir), 60)
        return _ok(authorized=bool(authorized))
    except Exception as e:  # noqa
        _log_exc(session_dir)
        return _err(_map_error(e), e)


def send_code(phone):
    async def go():
        sent = await _client.send_code_request(phone)
        _state["phone"] = phone
        _state["hash"] = sent.phone_code_hash
        return type(sent.type).__name__
    try:
        kind = _run(go(), 60)
        return _ok(kind=kind)
    except Exception as e:  # noqa
        _log_exc(_state["dir"])
        return _err(_map_error(e), e)


def sign_in_code(code):
    from telethon import errors

    async def go():
        try:
            await _client.sign_in(_state["phone"], code, phone_code_hash=_state["hash"])
            return "done"
        except errors.SessionPasswordNeededError:
            return "need_password"
    try:
        return _ok(status=_run(go(), 60))
    except Exception as e:  # noqa
        _log_exc(_state["dir"])
        return _err(_map_error(e), e)


def sign_in_password(password):
    async def go():
        await _client.sign_in(password=password)
        return "done"
    try:
        return _ok(status=_run(go(), 60))
    except Exception as e:  # noqa
        _log_exc(_state["dir"])
        return _err(_map_error(e), e)


def logout():
    async def go():
        try:
            await _client.log_out()
        except Exception:
            pass
    try:
        _run(go(), 30)
    except Exception:
        pass
    d = _state.get("dir")
    if d:
        for f in os.listdir(d):
            if f.startswith("munsif"):
                try:
                    os.remove(os.path.join(d, f))
                except OSError:
                    pass
    return _ok()


def list_chats():
    async def go():
        out = []
        async for d in _client.iter_dialogs(limit=400):
            ent = d.entity
            if getattr(ent, "bot", False) or getattr(ent, "deleted", False):
                continue
            if d.is_group:
                kind = "group"
            elif d.is_channel:
                kind = "channel"
            else:
                kind = "user"
            out.append({"id": d.id, "name": d.name or "(بدون اسم)", "kind": kind})
        return out
    try:
        return _ok(chats=_run(go(), 240))
    except Exception as e:  # noqa
        _log_exc(_state["dir"])
        return _err(_map_error(e), e)


def progress():
    return json.dumps(_progress, ensure_ascii=False)


def cancel():
    _cancel["flag"] = True
    return _ok()


def _set_progress(phase, done=0, total=0, text=""):
    _progress.update(phase=phase, done=done, total=total, text=text)


def _safe_name(s):
    s = re.sub(r"[^\w\-]+", "_", s, flags=re.UNICODE).strip("_")
    return s[:60] or "chat"


def export(chat_id, chat_name, from_ts, to_ts, gap_seconds, out_dir, file_stem):
    """بيشتغل على thread خلفي في جافا. بيرجع JSON فيه مسار الـ pptx."""
    from tg_source import fetch_chat, Cancelled
    _cancel["flag"] = False
    work = os.path.join(out_dir, "work_%d" % int(time.time()))
    try:
        os.makedirs(out_dir, exist_ok=True)
        from_ts = int(from_ts) if from_ts else None
        to_ts = int(to_ts) if to_ts else None
        msgs = _run(fetch_chat(_client, int(chat_id), from_ts, to_ts, work, _set_progress,
                               lambda: _cancel["flag"]))
        _set_progress("build", 0, 0)
        groups, stats = core.build_groups(msgs, int(gap_seconds), from_ts, to_ts)
        groups = core.split_oversized(groups)
        keys = {}
        paths = core.image_paths_of(groups)
        for i, p in enumerate(paths):
            if _cancel["flag"]:
                raise Cancelled()
            try:
                keys[p] = core.hash_file(p)
            except OSError:
                pass
            _set_progress("hash", i + 1, len(paths))
        groups, removed = core.dedupe_groups(groups, keys)
        if not groups:
            return _err("empty")
        out_path = os.path.join(out_dir, _safe_name(file_stem) + ".pptx")
        skipped = pptx_writer.build_pptx(
            groups, out_path, title=chat_name,
            on_slide=lambda n, t: _set_progress("build", n, t))
        _set_progress("done", len(groups), len(groups))
        return _ok(path=out_path, slides=len(groups), removed=removed,
                   skipped=skipped, messages=stats["messages"], stamps=stats["stamps"])
    except Cancelled:
        return _err("cancelled")
    except Exception as e:  # noqa
        _log_exc(_state["dir"])
        return _err(_map_error(e), e)
    finally:
        shutil.rmtree(work, ignore_errors=True)
