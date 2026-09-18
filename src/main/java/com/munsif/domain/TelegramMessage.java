package com.munsif.domain;

import java.io.File;
import java.util.List;

/**
 * تمثيل نظيف لرسالة تليجرام واحدة، مستقل تمامًا عن شكل ملف result.json الأصلي.
 * طبقة الـ infrastructure هي المسؤولة عن تحويل JSON لكائن زي ده.
 */
public class TelegramMessage {
    public final long id;
    public final String type;           // "message" أو "service"
    public final String senderKey;      // معرف داخلي للمرسل — يُستخدم للتجميع بس، لا يظهر أبدًا في الناتج
    public final long timestampSeconds;
    public final List<TextSegment> segments;
    public final String mediaType;      // sticker / voice_message / video_file ... أو null
    public final File photo;            // مسار الصورة الأصلية لو موجودة وموجودة فعليًا على القرص
    public final File thumbnail;        // مسار معاينة (لفيديو/ملصق) لو موجودة
    public final File attachedFile;     // مسار ملف عام مرفق (مستند مثلًا) لو موجود

    public TelegramMessage(long id, String type, String senderKey, long timestampSeconds,
                            List<TextSegment> segments, String mediaType,
                            File photo, File thumbnail, File attachedFile) {
        this.id = id;
        this.type = type;
        this.senderKey = senderKey;
        this.timestampSeconds = timestampSeconds;
        this.segments = segments;
        this.mediaType = mediaType;
        this.photo = photo;
        this.thumbnail = thumbnail;
        this.attachedFile = attachedFile;
    }

    public boolean isRegularMessage() {
        return "message".equals(type);
    }
}
