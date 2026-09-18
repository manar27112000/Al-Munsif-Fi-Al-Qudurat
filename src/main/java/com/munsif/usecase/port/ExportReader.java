package com.munsif.usecase.port;

import java.io.File;
import java.io.IOException;

/**
 * المنفذ (Port) اللي بيوصف إزاي نجيب رسائل من أي مصدر تصدير.
 * الحالة الاستخدامية (usecase) بتعتمد على الواجهة دي بس، مش على أي تفصيلة
 * زي شكل ملف JSON — التفاصيل دي في طبقة الـ infrastructure.
 */
public interface ExportReader {
    LoadedExport load(File exportDir) throws IOException;
}
