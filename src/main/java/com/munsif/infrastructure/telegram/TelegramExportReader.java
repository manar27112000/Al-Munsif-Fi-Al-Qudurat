package com.munsif.infrastructure.telegram;

import com.munsif.domain.TelegramMessage;
import com.munsif.domain.TextSegment;
import com.munsif.usecase.port.ExportReader;
import com.munsif.usecase.port.LoadedExport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * ينفّذ ExportReader بقراءة result.json اللي بيصدّره Telegram Desktop
 * (خاصية "Export chat history")، ويحوّله لكائنات TelegramMessage نضيفة.
 * هي الطبقة الوحيدة في المشروع اللي بتعرف حاجة عن شكل ملف JSON الأصلي.
 */
public class TelegramExportReader implements ExportReader {

    @Override
    public LoadedExport load(File exportDir) throws IOException {
        File resultFile = findResultJson(exportDir);
        File baseDir = resultFile.getParentFile();

        String content = Files.readString(resultFile.toPath(), StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        JSONObject data = new JSONObject(content);

        if (data.has("messages")) {
            String name = data.optString("name", "chat");
            return new LoadedExport(name, toDomainMessages(data.getJSONArray("messages"), baseDir));
        }

        if (data.has("chats")) {
            JSONArray chats = data.getJSONObject("chats").getJSONArray("list");
            JSONObject best = null;
            int bestCount = -1;
            for (int i = 0; i < chats.length(); i++) {
                JSONObject c = chats.getJSONObject(i);
                JSONArray msgs = c.optJSONArray("messages");
                if (msgs != null && msgs.length() > bestCount) {
                    bestCount = msgs.length();
                    best = c;
                }
            }
            if (best == null) {
                throw new IOException("\u0645\u0641\u064a\u0634 \u0623\u064a \u0634\u0627\u062a \u0641\u064a\u0647 \u0631\u0633\u0627\u0626\u0644 \u062c\u0648\u0647 \u0647\u0630\u0627 \u0627\u0644\u062a\u0635\u062f\u064a\u0631.");
            }
            String name = best.optString("name", "chat");
            return new LoadedExport(name, toDomainMessages(best.getJSONArray("messages"), baseDir));
        }

        throw new IOException("\u0634\u0643\u0644 result.json \u0645\u0634 \u0645\u062a\u0639\u0631\u0641 \u0639\u0644\u064a\u0647.");
    }

    private File findResultJson(File exportDir) throws IOException {
        File direct = new File(exportDir, "result.json");
        if (direct.exists()) return direct;

        try (Stream<Path> walk = Files.walk(exportDir.toPath())) {
            Optional<Path> found = walk
                    .filter(p -> p.getFileName().toString().equals("result.json"))
                    .findFirst();
            if (found.isPresent()) return found.get().toFile();
        }
        throw new IOException("\u0645\u0641\u064a\u0634 result.json \u062c\u0648\u0647 " + exportDir);
    }

    private List<TelegramMessage> toDomainMessages(JSONArray raw, File baseDir) {
        List<TelegramMessage> out = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            out.add(toDomainMessage(raw.getJSONObject(i), baseDir));
        }
        return out;
    }

    private TelegramMessage toDomainMessage(JSONObject m, File baseDir) {
        long id = m.optLong("id", 0);
        String type = m.optString("type", "");
        String senderKey = m.optString("from_id", m.optString("from", ""));

        long ts;
        try {
            ts = Long.parseLong(m.optString("date_unixtime", "0"));
        } catch (NumberFormatException e) {
            ts = 0;
        }

        List<TextSegment> segments = extractSegments(m.opt("text"));

        String mediaType = m.has("media_type") ? m.optString("media_type", null) : null;
        File photo = resolveRelative(baseDir, m.has("photo") ? m.optString("photo", null) : null);
        File thumbnail = resolveRelative(baseDir, m.has("thumbnail") ? m.optString("thumbnail", null) : null);
        File attachedFile = resolveRelative(baseDir, m.has("file") ? m.optString("file", null) : null);

        return new TelegramMessage(id, type, senderKey, ts, segments, mediaType, photo, thumbnail, attachedFile);
    }

    private List<TextSegment> extractSegments(Object text) {
        List<TextSegment> segments = new ArrayList<>();
        if (text instanceof String s) {
            if (!s.isEmpty()) segments.add(new TextSegment(s, "plain"));
        } else if (text instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof String s) {
                    if (!s.isEmpty()) segments.add(new TextSegment(s, "plain"));
                } else if (item instanceof JSONObject entity) {
                    String t = entity.optString("text", "");
                    if (t.isEmpty()) continue;
                    segments.add(new TextSegment(t, entity.optString("type", "plain")));
                }
            }
        }
        return segments;
    }

    private File resolveRelative(File baseDir, String relativePath) {
        if (relativePath == null) return null;
        File f = new File(baseDir, relativePath);
        return f.exists() ? f : null;
    }
}
