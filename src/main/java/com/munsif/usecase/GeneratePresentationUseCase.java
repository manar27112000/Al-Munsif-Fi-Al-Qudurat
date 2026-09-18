package com.munsif.usecase;

import com.munsif.domain.MessageGroup;
import com.munsif.domain.TelegramMessage;
import com.munsif.usecase.port.ExportReader;
import com.munsif.usecase.port.LoadedExport;
import com.munsif.usecase.port.PresentationExporter;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * الحالة الاستخدامية الرئيسية: من فولدر تصدير تليجرام لملف PowerPoint جاهز.
 * الكلاس ده مش عارف حاجة عن JavaFX ولا Apache POI ولا org.json — كله بييجي
 * عن طريق الـ ports، فسهل نستبدل أي جزء (مثلاً نضيف مصدر تصدير تاني، أو نصدّر
 * لصيغة غير PowerPoint) من غير ما نلمس المنطق هنا.
 */
public class GeneratePresentationUseCase {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);
    }

    private static final int MAX_WEIGHT_PER_SLIDE = 1400;

    private final ExportReader reader;
    private final PresentationExporter exporter;

    public GeneratePresentationUseCase(ExportReader reader, PresentationExporter exporter) {
        this.reader = reader;
        this.exporter = exporter;
    }

    public File execute(File exportDir, LocalDate fromDate, LocalDate toDate, int gapSeconds,
                         ProgressListener listener) throws IOException {
        LoadedExport loaded = reader.load(exportDir);
        listener.onProgress("الشات: " + loaded.chatName() + " — عدد الرسائل الخام: " + loaded.messages().size());

        Long fromTs = fromDate != null
                ? fromDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                : null;
        Long toTs = toDate != null
                ? toDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                : null;

        List<TelegramMessage> filtered = filterByDate(loaded.messages(), fromTs, toTs);
        listener.onProgress("بعد فلترة التاريخ: " + filtered.size() + " رسالة");

        List<MessageGroup> groups = Grouper.buildGroups(filtered, gapSeconds);

        int beforeDedup = groups.size();
        groups = Deduplicator.removeDuplicateGroups(groups);
        int removed = beforeDedup - groups.size();
        if (removed > 0) {
            listener.onProgress("تم حذف " + removed + " رسالة/مجموعة مكررة");
        }

        groups = Grouper.splitOversizedGroups(groups, MAX_WEIGHT_PER_SLIDE);
        listener.onProgress("عدد السلايدات: " + groups.size());

        File outDir = new File(exportDir, "output");
        outDir.mkdirs();
        String safeName = sanitizeFileName(loaded.chatName());
        File outFile = new File(outDir, safeName + "_slides.pptx");

        exporter.export(groups, outFile);
        listener.onProgress("تم الحفظ في: " + outFile.getAbsolutePath());
        return outFile;
    }

    private static List<TelegramMessage> filterByDate(List<TelegramMessage> messages, Long fromTs, Long toTs) {
        if (fromTs == null && toTs == null) return messages;
        List<TelegramMessage> result = new ArrayList<>();
        for (TelegramMessage m : messages) {
            if (fromTs != null && m.timestampSeconds < fromTs) continue;
            if (toTs != null && m.timestampSeconds >= toTs) continue;
            result.add(m);
        }
        return result;
    }

    private static String sanitizeFileName(String name) {
        String safe = name.replaceAll("[^\\p{L}\\p{N}_\\-]+", "_");
        return safe.isBlank() ? "chat" : safe;
    }
}
