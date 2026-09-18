package com.munsif.usecase;

import com.munsif.domain.TelegramMessage;
import com.munsif.domain.TextRun;
import com.munsif.domain.TextSegment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * قواعد العمل الخاصة بتنظيف محتوى الرسائل: حذف أي @mention، كشف رسائل
 * التوقيع/التحويل الآلي، وترجمة نوع الميديا لملاحظة نصية عربية.
 * الكلاس ده متعمّد يكون مستقل عن أي مكتبة خارجية (لا org.json ولا Apache POI).
 */
public class TextProcessor {

    public static final Pattern NEW_TOPIC_RE = Pattern.compile(
            "^\\s*(?:\\d{1,3}\\s*[.)\\-\u2013:]|\u0633[\u0624\u0654]?\u0627\u0644\\s*\\d*\\s*[:\\-]?|[Qq]\\s*\\d*\\s*[:\\-])"
    );
    public static final Pattern ARABIC_RE = Pattern.compile("[\u0600-\u06FF]");

    private static final Pattern HANDLE_RE = Pattern.compile(
            "\\(\\s*@[A-Za-z0-9_]{3,32}\\s*\\)|@[A-Za-z0-9_]{3,32}"
    );
    private static final Pattern STAMP_ID_RE = Pattern.compile("\uD83C\uDD94\\s*\\d+"); // 🆔 + أرقام

    private static final String[][] STAMP_PHRASE_PAIRS = {
            {"\u0627\u0644\u0631\u0633\u0627\u0644\u0629 \u0645\u0648\u062c\u0647\u0629 \u0645\u0646 \u062d\u0633\u0627\u0628", "\u0628\u0648\u0627\u0633\u0637\u0647"},
            {"\u0627\u0644\u0631\u0633\u0627\u0644\u0629 \u0645\u0648\u062c\u0647\u0629 \u0645\u0646 \u062d\u0633\u0627\u0628", "\u0628\u0648\u0627\u0633\u0637\u0629"},
    };

    private static final Map<String, String> MEDIA_LABELS = new HashMap<>();
    static {
        MEDIA_LABELS.put("sticker", "[\u0645\u0644\u0635\u0642]");
        MEDIA_LABELS.put("voice_message", "[\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062a\u064a\u0629]");
        MEDIA_LABELS.put("video_message", "[\u0641\u064a\u062f\u064a\u0648 \u062f\u0627\u0626\u0631\u064a]");
        MEDIA_LABELS.put("animation", "[GIF \u0645\u062a\u062d\u0631\u0643]");
        MEDIA_LABELS.put("video_file", "[\u0641\u064a\u062f\u064a\u0648]");
        MEDIA_LABELS.put("audio_file", "[\u0645\u0644\u0641 \u0635\u0648\u062a\u064a]");
    }

    public static String stripHandles(String text) {
        String cleaned = HANDLE_RE.matcher(text).replaceAll("");
        return cleaned.replaceAll("[ \\t]{2,}", " ");
    }

    public static boolean isIdentityStamp(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String[] pair : STAMP_PHRASE_PAIRS) {
            if (text.contains(pair[0]) && text.contains(pair[1])) return true;
        }
        return STAMP_ID_RE.matcher(text).find();
    }

    public static List<TextRun> extractRuns(TelegramMessage msg) {
        List<TextRun> runs = new ArrayList<>();
        for (TextSegment seg : msg.segments) {
            String cleaned = stripHandles(seg.text);
            if (cleaned.strip().isEmpty()) continue;
            boolean bold = "bold".equals(seg.entityType);
            boolean italic = "italic".equals(seg.entityType);
            runs.add(new TextRun(cleaned, bold, italic));
        }
        return runs;
    }

    public static String plainText(TelegramMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (TextRun r : extractRuns(msg)) sb.append(r.text);
        return sb.toString();
    }

    /** الصورة اللي هتتحط فعليًا للرسالة دي: تفضيل الصورة الأصلية، وإلا معاينة (thumbnail). */
    public static File resolveDisplayImage(TelegramMessage msg) {
        if (msg.photo != null) return msg.photo;
        if (msg.thumbnail != null) return msg.thumbnail;
        return null;
    }

    public static String mediaNote(TelegramMessage msg, File resolvedImage) {
        if (msg.mediaType != null && MEDIA_LABELS.containsKey(msg.mediaType) && resolvedImage == null) {
            return MEDIA_LABELS.get(msg.mediaType);
        }
        if (msg.attachedFile != null && msg.photo == null && msg.mediaType == null) {
            return "[\u0645\u0644\u0641 \u0645\u0631\u0641\u0642: " + msg.attachedFile.getName() + "]";
        }
        return null;
    }
}
