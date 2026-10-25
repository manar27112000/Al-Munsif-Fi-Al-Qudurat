package com.almunsif.qudurat;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** AES-IGE (اللي تليجرام بيستخدمه) — أسرع بكتير من نسخة البايثون الصافية. */
public final class Ige {
    private Ige() {}

    public static byte[] decrypt(byte[] cipherText, byte[] key, byte[] iv) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        int blocks = cipherText.length / 16;
        byte[] out = new byte[blocks * 16];
        byte[] iv1 = new byte[16];
        byte[] iv2 = new byte[16];
        System.arraycopy(iv, 0, iv1, 0, 16);
        System.arraycopy(iv, 16, iv2, 0, 16);
        byte[] tmp = new byte[16];
        byte[] dec = new byte[16];
        for (int b = 0; b < blocks; b++) {
            int off = b * 16;
            for (int i = 0; i < 16; i++) tmp[i] = (byte) (cipherText[off + i] ^ iv2[i]);
            aes.update(tmp, 0, 16, dec, 0);
            for (int i = 0; i < 16; i++) {
                byte p = (byte) (dec[i] ^ iv1[i]);
                out[off + i] = p;
            }
            System.arraycopy(cipherText, off, iv1, 0, 16);
            System.arraycopy(out, off, iv2, 0, 16);
        }
        return out;
    }

    public static byte[] encrypt(byte[] plainText, byte[] key, byte[] iv) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        int blocks = plainText.length / 16;
        byte[] out = new byte[blocks * 16];
        byte[] iv1 = new byte[16];
        byte[] iv2 = new byte[16];
        System.arraycopy(iv, 0, iv1, 0, 16);
        System.arraycopy(iv, 16, iv2, 0, 16);
        byte[] tmp = new byte[16];
        byte[] enc = new byte[16];
        for (int b = 0; b < blocks; b++) {
            int off = b * 16;
            for (int i = 0; i < 16; i++) tmp[i] = (byte) (plainText[off + i] ^ iv1[i]);
            aes.update(tmp, 0, 16, enc, 0);
            for (int i = 0; i < 16; i++) out[off + i] = (byte) (enc[i] ^ iv2[i]);
            System.arraycopy(out, off, iv1, 0, 16);
            System.arraycopy(plainText, off, iv2, 0, 16);
        }
        return out;
    }
}
