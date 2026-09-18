package com.munsif.usecase.port;

import com.munsif.domain.TelegramMessage;

import java.util.List;

/** نتيجة قراءة تصدير كامل: اسم الشات وكل رسائله كـ TelegramMessage نضيفة. */
public record LoadedExport(String chatName, List<TelegramMessage> messages) {
}
