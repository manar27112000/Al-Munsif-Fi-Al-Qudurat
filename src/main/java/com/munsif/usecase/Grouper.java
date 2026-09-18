package com.munsif.usecase;

import com.munsif.domain.ContentItem;
import com.munsif.domain.MessageGroup;
import com.munsif.domain.TelegramMessage;
import com.munsif.domain.TextRun;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** يجمّع رسائل تليجرام في مجموعات (= سلايد واحدة مبدئيًا)، وبيقسم أي مجموعة كبيرة أوي. */
public class Grouper {

    private static final int IMAGE_WEIGHT = 350;

    public static List<MessageGroup> buildGroups(List<TelegramMessage> messages, int gapSeconds) {
        List<MessageGroup> groups = new ArrayList<>();
        MessageGroup current = null;
        Long prevTime = null;
        String prevSender = null;

        for (TelegramMessage msg : messages) {
            if (!msg.isRegularMessage()) continue; // نتجاهل رسائل الخدمة (انضمام، تثبيت، إلخ)

            String text = TextProcessor.plainText(msg).strip();
            if (TextProcessor.isIdentityStamp(text)) continue;

            File image = TextProcessor.resolveDisplayImage(msg);
            String note = TextProcessor.mediaNote(msg, image);

            if (text.isEmpty() && note == null && image == null) continue;

            boolean startsNewTopic = !text.isEmpty() && TextProcessor.NEW_TOPIC_RE.matcher(text).find();
            boolean sameSender = msg.senderKey.equals(prevSender);
            boolean withinGap = prevTime != null && (msg.timestampSeconds - prevTime) <= gapSeconds;

            ContentItem item = new ContentItem(TextProcessor.extractRuns(msg), note, image);

            if (current != null && sameSender && withinGap && !(startsNewTopic && !current.items.isEmpty())) {
                current.items.add(item);
            } else {
                current = new MessageGroup();
                current.items.add(item);
                groups.add(current);
            }

            prevTime = msg.timestampSeconds;
            prevSender = msg.senderKey;
        }
        return groups;
    }

    private static int itemWeight(ContentItem item) {
        int w = 0;
        for (TextRun r : item.runs) w += r.text.length();
        if (item.note != null) w += item.note.length();
        if (item.image != null) w += IMAGE_WEIGHT;
        return w;
    }

    private static int groupWeight(MessageGroup g) {
        int w = 0;
        for (ContentItem item : g.items) w += itemWeight(item);
        return w;
    }

    public static List<MessageGroup> splitOversizedGroups(List<MessageGroup> groups, int maxWeight) {
        List<MessageGroup> result = new ArrayList<>();
        for (MessageGroup g : groups) {
            if (groupWeight(g) <= maxWeight) {
                result.add(g);
                continue;
            }
            MessageGroup chunk = new MessageGroup();
            int chunkWeight = 0;
            for (ContentItem item : g.items) {
                int w = itemWeight(item);
                if (!chunk.items.isEmpty() && chunkWeight + w > maxWeight) {
                    result.add(chunk);
                    chunk = new MessageGroup();
                    chunkWeight = 0;
                }
                chunk.items.add(item);
                chunkWeight += w;
            }
            if (!chunk.items.isEmpty()) result.add(chunk);
        }
        return result;
    }
}
