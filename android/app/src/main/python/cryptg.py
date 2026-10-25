# -*- coding: utf-8 -*-
"""بديل لمكتبة cryptg: تشفير AES-IGE سريع عن طريق جافا (جوه أندرويد).
تليجرام (Telethon) بيدوّر على مكتبة اسمها cryptg، ولو لقاها بيستخدمها.
على أي جهاز مفيهوش جافا الاستيراد بيفشل وTelethon بيرجع للبايثون العادي."""
try:
    from java import jclass
    _Ige = jclass("com.almunsif.qudurat.Ige")
except Exception as _e:  # noqa
    raise ImportError("java bridge not available") from _e


def encrypt_ige(plain_text, key, iv):
    return bytes(_Ige.encrypt(bytes(plain_text), bytes(key), bytes(iv)))


def decrypt_ige(cipher_text, key, iv):
    return bytes(_Ige.decrypt(bytes(cipher_text), bytes(key), bytes(iv)))
