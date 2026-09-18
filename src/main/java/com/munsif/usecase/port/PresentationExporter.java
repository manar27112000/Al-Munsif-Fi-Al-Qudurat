package com.munsif.usecase.port;

import com.munsif.domain.MessageGroup;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * المنفذ (Port) اللي بيوصف إزاي نحوّل مجموعات الرسائل لملف عرض تقديمي.
 * التفاصيل الفعلية (Apache POI، شكل الـ pptx) في طبقة الـ infrastructure.
 */
public interface PresentationExporter {
    void export(List<MessageGroup> groups, File outputFile) throws IOException;
}
