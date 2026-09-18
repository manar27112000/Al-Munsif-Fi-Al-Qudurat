package com.munsif.usecase;

import com.munsif.domain.ContentItem;
import com.munsif.domain.MessageGroup;
import com.munsif.domain.TextRun;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * يشيل أي مجموعة رسائل (سلايد) محتواها (نص + صور) مطابق تمامًا لمجموعة سابقة
 * ظهرت قبل كده في نفس الفترة — بيسيب أول ظهور بس ويحذف اللي بعده.
 */
public class Deduplicator {

    public static List<MessageGroup> removeDuplicateGroups(List<MessageGroup> groups) {
        Set<String> seen = new HashSet<>();
        List<MessageGroup> result = new ArrayList<>();

        for (MessageGroup g : groups) {
            String sig = signature(g);
            if (sig.isEmpty()) {
                result.add(g); // مجموعة فاضية فعليًا (نادر) — سيبيها زي ما هي
                continue;
            }
            if (seen.add(sig)) {
                result.add(g);
            }
            // لو التوقيع اتشاف قبل كده، المجموعة دي بتتجاهل (تتحذف)
        }
        return result;
    }

    private static String signature(MessageGroup g) {
        StringBuilder sb = new StringBuilder();
        for (ContentItem item : g.items) {
            for (TextRun r : item.runs) sb.append(r.text);
            if (item.note != null) sb.append(item.note);
            if (item.image != null) sb.append("|IMG:").append(hashFile(item.image));
            sb.append('\u0001'); // فاصل بين الرسائل جوه نفس المجموعة
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String hashFile(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(f.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return f.getName(); // fallback بسيط لو فشلت قراءة الملف
        }
    }
}
