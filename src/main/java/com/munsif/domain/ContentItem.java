package com.munsif.domain;

import java.io.File;
import java.util.List;

public class ContentItem {
    public final List<TextRun> runs;
    public final String note;
    public final File image;

    public ContentItem(List<TextRun> runs, String note, File image) {
        this.runs = runs;
        this.note = note;
        this.image = image;
    }
}
