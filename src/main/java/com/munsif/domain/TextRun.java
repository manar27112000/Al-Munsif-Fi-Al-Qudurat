package com.munsif.domain;

public class TextRun {
    public final String text;
    public final boolean bold;
    public final boolean italic;

    public TextRun(String text, boolean bold, boolean italic) {
        this.text = text;
        this.bold = bold;
        this.italic = italic;
    }
}
