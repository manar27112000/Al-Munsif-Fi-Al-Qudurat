package com.munsif.infrastructure.pptx;

import com.munsif.domain.ContentItem;
import com.munsif.domain.MessageGroup;
import com.munsif.domain.TextRun;
import com.munsif.usecase.TextProcessor;
import com.munsif.usecase.port.PresentationExporter;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ينفّذ PresentationExporter باستخدام Apache POI. كل تفاصيل بناء ملف الـ
 * pptx (المقاسات، الخطوط، RTL) محصورة هنا بس — باقي المشروع مش عارف حاجة
 * عن Apache POI خالص.
 */
public class PptxPresentationWriter implements PresentationExporter {

    private static final double MARGIN_PT = 36; // 0.5 inch
    private static final double FONT_SIZE_PT = 20;
    private static final String FONT_NAME = "Calibri";
    private static final double IMAGE_GAP_PT = 8;

    @Override
    public void export(List<MessageGroup> groups, File outputFile) throws IOException {
        XMLSlideShow ppt = new XMLSlideShow();
        try {
            ppt.setPageSize(new Dimension(960, 540)); // 13.333 x 7.5 in @ 72pt/in = 16:9

            int total = groups.size();
            int idx = 1;
            for (MessageGroup g : groups) {
                addSlide(ppt, g, idx, total);
                idx++;
            }

            outputFile.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                ppt.write(out);
            }
        } finally {
            ppt.close();
        }
    }

    private void addSlide(XMLSlideShow ppt, MessageGroup group, int idx, int total) {
        XSLFSlide slide = ppt.createSlide();
        double slideW = ppt.getPageSize().getWidth();
        double slideH = ppt.getPageSize().getHeight();
        double contentW = slideW - 2 * MARGIN_PT;
        double contentH = slideH - 2 * MARGIN_PT;

        List<File> images = new ArrayList<>();
        List<List<TextRun>> textBlocks = new ArrayList<>();
        for (ContentItem item : group.items) {
            if (item.image != null) images.add(item.image);
            if (!item.runs.isEmpty()) textBlocks.add(item.runs);
            if (item.note != null) {
                List<TextRun> noteRun = new ArrayList<>();
                noteRun.add(new TextRun(item.note, false, true));
                textBlocks.add(noteRun);
            }
        }

        StringBuilder allText = new StringBuilder();
        for (List<TextRun> block : textBlocks) for (TextRun r : block) allText.append(r.text);
        boolean isArabic = TextProcessor.ARABIC_RE.matcher(allText).find();

        double cursorY = MARGIN_PT;

        if (!images.isEmpty()) {
            double maxImgH = contentH * (textBlocks.isEmpty() ? 0.9 : 0.55);
            List<double[]> sizes = new ArrayList<>();
            for (File img : images) sizes.add(getImageSize(img));

            double[] widths = new double[sizes.size()];
            double rowH = layoutImagesRow(sizes, contentW, maxImgH, widths);

            double totalRowW = 0;
            for (double w : widths) totalRowW += w;
            totalRowW += IMAGE_GAP_PT * (widths.length - 1);

            double x = MARGIN_PT + (contentW - totalRowW) / 2;
            for (int i = 0; i < images.size(); i++) {
                File imgFile = images.get(i);
                double w = widths[i];
                try {
                    byte[] data = Files.readAllBytes(imgFile.toPath());
                    PictureData.PictureType type = detectPictureType(imgFile);
                    XSLFPictureData pd = ppt.addPicture(data, type);
                    XSLFPictureShape pic = slide.createPicture(pd);
                    pic.setAnchor(new Rectangle2D.Double(x, cursorY, w, rowH));
                } catch (Exception e) {
                    System.err.println("تحذير: فشل إدراج الصورة " + imgFile + ": " + e.getMessage());
                }
                x += w + IMAGE_GAP_PT;
            }
            cursorY += rowH + 18; // ~0.25in
        }

        if (!textBlocks.isEmpty()) {
            double boxH = Math.max(contentH - (cursorY - MARGIN_PT), 36);
            XSLFTextBox tb = slide.createTextBox();
            tb.setAnchor(new Rectangle2D.Double(MARGIN_PT, cursorY, contentW, boxH));
            tb.setWordWrap(true);
            tb.setTextAutofit(TextShape.TextAutofit.NORMAL); // يصغّر الخط تلقائيًا بدل ما يفيض بره الصندوق

            for (List<TextRun> block : textBlocks) {
                XSLFTextParagraph p = tb.addNewTextParagraph();
                p.setTextAlign(isArabic ? TextParagraph.TextAlign.RIGHT : TextParagraph.TextAlign.LEFT);
                if (isArabic) setRtl(p);
                p.setSpaceAfter(10.0);
                for (TextRun r : block) {
                    XSLFTextRun run = p.addNewTextRun();
                    run.setText(r.text);
                    run.setFontSize(FONT_SIZE_PT);
                    run.setFontFamily(FONT_NAME);
                    run.setBold(r.bold);
                    run.setItalic(r.italic);
                }
            }
        }

        // رقم صفحة صغير في الزاوية
        XSLFTextBox foot = slide.createTextBox();
        double footW = 60, footH = 18;
        foot.setAnchor(new Rectangle2D.Double(slideW - MARGIN_PT - footW, slideH - 26, footW, footH));
        XSLFTextParagraph fp = foot.addNewTextParagraph();
        fp.setTextAlign(TextParagraph.TextAlign.RIGHT);
        XSLFTextRun fr = fp.addNewTextRun();
        fr.setText(idx + "/" + total);
        fr.setFontSize(10.0);
    }

    private double[] getImageSize(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img != null) return new double[]{img.getWidth(), img.getHeight()};
        } catch (IOException ignored) {
        }
        return new double[]{4, 3};
    }

    /** يفعّل اتجاه الكتابة من اليمين لليسار على مستوى الفقرة — ضروري عشان النص العربي
     * يتصف صح جوه PowerPoint (من غيرها الحروف بتتقلب/تتصف غلط). */
    private void setRtl(XSLFTextParagraph paragraph) {
        CTTextParagraph ctp = paragraph.getXmlObject();
        CTTextParagraphProperties pPr = ctp.isSetPPr() ? ctp.getPPr() : ctp.addNewPPr();
        pPr.setRtl(true);
    }

    /** يحسب أبعاد الصور في صف واحد بحيث تتناسب مع العرض والارتفاع المتاحين، ويملأ outWidths. يرجع ارتفاع الصف. */
    private double layoutImagesRow(List<double[]> sizes, double maxWidth, double maxHeight, double[] outWidths) {
        int n = sizes.size();
        double usableWidth = maxWidth - IMAGE_GAP_PT * (n - 1);
        double rowHeight = maxHeight;

        double[] aspectRatios = new double[n];
        double totalWidth = 0;
        for (int i = 0; i < n; i++) {
            aspectRatios[i] = sizes.get(i)[0] / sizes.get(i)[1];
            totalWidth += aspectRatios[i] * rowHeight;
        }
        if (totalWidth > usableWidth) {
            rowHeight *= usableWidth / totalWidth;
        }
        for (int i = 0; i < n; i++) outWidths[i] = aspectRatios[i] * rowHeight;
        return rowHeight;
    }

    private PictureData.PictureType detectPictureType(File f) {
        String name = f.getName().toLowerCase();
        if (name.endsWith(".png")) return PictureData.PictureType.PNG;
        if (name.endsWith(".gif")) return PictureData.PictureType.GIF;
        if (name.endsWith(".bmp")) return PictureData.PictureType.BMP;
        return PictureData.PictureType.JPEG;
    }
}
