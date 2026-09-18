package com.munsif.domain;

/**
 * جزء نصي خام زي ما جه من تصدير تليجرام، قبل أي تنظيف (زي حذف الـ @mentions
 * أو كشف رسائل التوقيع الآلي). النوع (entityType) بييجي من تليجرام نفسه:
 * "plain", "bold", "italic"... إلخ.
 */
public class TextSegment {
    public final String text;
    public final String entityType;

    public TextSegment(String text, String entityType) {
        this.text = text;
        this.entityType = entityType;
    }
}
