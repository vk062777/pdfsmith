package com.vk062777.pdfsmith.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * NovaPDF — the offline document-processing core.
 *
 * <p>Every method is a pure file-in / file-out operation built on the Apache PDFBox
 * port for Android. No UI, no network: the whole engine is offline and deterministic,
 * which is what lets it be unit-tested on the JVM as well as on-device.</p>
 */
public final class PdfEngine {

    private PdfEngine() { }

    // ---------------------------------------------------------------------
    // Small value types
    // ---------------------------------------------------------------------

    public static final class TextStamp {
        public int page;
        public float x, y, size;
        public int color;
        public String text;
        public TextStamp(int page, float x, float y, float size, int color, String text) {
            this.page = page; this.x = x; this.y = y; this.size = size; this.color = color; this.text = text;
        }
    }

    public static final class ImageStamp {
        public int page;
        public float x, y, w, h;
        public File image;
        public ImageStamp(int page, float x, float y, float w, float h, File image) {
            this.page = page; this.x = x; this.y = y; this.w = w; this.h = h; this.image = image;
        }
    }

    public static final class HighlightStamp {
        public int page;
        public float x, y, w, h;
        public int color;
        public HighlightStamp(int page, float x, float y, float w, float h, int color) {
            this.page = page; this.x = x; this.y = y; this.w = w; this.h = h; this.color = color;
        }
    }

    public static final class FormField {
        public String name;
        public String type;
        public String value;
        public List<String> options = new ArrayList<>();
        public boolean readOnly;
    }

    /** Options for {@link #watermarkText}. */
    public static final class WatermarkOptions {
        public String text = "CONFIDENTIAL";
        /** 0 = choose automatically from the page size. */
        public float fontSize = 0f;
        public float opacity = 0.25f;
        public int color = 0xFFFF0000;
        public float angle = 45f;
        /** Helvetica | Times | Courier */
        public String font = "Helvetica";
        public boolean bold = true;
        public boolean italic = false;
        /** Place each page's mark at a different random spot. */
        public boolean randomPosition = false;
    }

    // ---------------------------------------------------------------------
    // Loading helpers
    // ---------------------------------------------------------------------

    private static PDDocument open(File f) throws Exception {
        return PDDocument.load(f);
    }

    private static PDDocument open(File f, String password) throws Exception {
        if (password == null || password.isEmpty()) return PDDocument.load(f);
        return PDDocument.load(f, password);
    }

    /** True if the file is encrypted at all (even if it opens without a password). */
    public static boolean isEncrypted(File f) {
        try (PDDocument d = PDDocument.load(f)) {
            return d.isEncrypted();
        } catch (Exception e) {
            return true;
        }
    }

    /** True only when the file cannot be opened without a password. */
    public static boolean needsPassword(File f) {
        try (PDDocument d = PDDocument.load(f)) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // Basic information
    // ---------------------------------------------------------------------

    public static int pageCount(File f) throws Exception {
        try (PDDocument d = open(f)) {
            return d.getNumberOfPages();
        }
    }

    public static int pageCount(File f, String password) throws Exception {
        try (PDDocument d = open(f, password)) {
            return d.getNumberOfPages();
        }
    }

    public static List<float[]> pageSizes(File f) throws Exception {
        List<float[]> out = new ArrayList<>();
        try (PDDocument d = open(f)) {
            for (PDPage p : d.getPages()) {
                PDRectangle r = p.getCropBox();
                out.add(new float[]{r.getWidth(), r.getHeight()});
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Merge
    // ---------------------------------------------------------------------

    public static void merge(List<File> inputs, File out) throws Exception {
        if (inputs == null || inputs.size() < 2) throw new IllegalArgumentException("Need at least two PDFs to merge");
        PDFMergerUtility merger = new PDFMergerUtility();
        for (File f : inputs) merger.addSource(f);
        merger.setDestinationFileName(out.getAbsolutePath());
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());
    }

    // ---------------------------------------------------------------------
    // Page organisation
    // ---------------------------------------------------------------------

    public static void keepPages(File src, List<Integer> pages1based, File out) throws Exception {
        try (PDDocument in = open(src); PDDocument outDoc = new PDDocument()) {
            int total = in.getNumberOfPages();
            for (int p : pages1based) {
                if (p < 1 || p > total) throw new IllegalArgumentException("Page " + p + " out of range (1.." + total + ")");
                outDoc.importPage(in.getPage(p - 1));
            }
            outDoc.save(out);
        }
    }

    public static void removePages(File src, List<Integer> pages1based, File out) throws Exception {
        try (PDDocument in = open(src)) {
            int total = in.getNumberOfPages();
            Set<Integer> drop = new HashSet<>(pages1based);
            List<Integer> keep = new ArrayList<>();
            for (int i = 1; i <= total; i++) if (!drop.contains(i)) keep.add(i);
            if (keep.isEmpty()) throw new IllegalArgumentException("Cannot remove every page");
            keepPages(src, keep, out);
        }
    }

    public static void reorderPages(File src, List<Integer> order1based, File out) throws Exception {
        try (PDDocument in = open(src)) {
            int total = in.getNumberOfPages();
            if (order1based.size() != total) throw new IllegalArgumentException("Order must list all " + total + " pages");
            keepPages(src, order1based, out);
        }
    }

    public static void rotatePages(File src, List<Integer> pages1based, int degrees, File out) throws Exception {
        int d = ((degrees % 360) + 360) % 360;
        try (PDDocument in = open(src)) {
            int total = in.getNumberOfPages();
            Set<Integer> set = (pages1based == null || pages1based.isEmpty()) ? null : new HashSet<>(pages1based);
            for (int i = 1; i <= total; i++) {
                if (set != null && !set.contains(i)) continue;
                PDPage p = in.getPage(i - 1);
                p.setRotation((p.getRotation() + d) % 360);
            }
            in.save(out);
        }
    }

    /** Reorder pages and apply a per-output-page rotation delta in one pass. */
    public static void rebuild(File src, List<Integer> order1based, List<Integer> rotationDeltas, File out) throws Exception {
        try (PDDocument in = open(src); PDDocument outDoc = new PDDocument()) {
            int total = in.getNumberOfPages();
            for (int i = 0; i < order1based.size(); i++) {
                int p = order1based.get(i);
                if (p < 1 || p > total) throw new IllegalArgumentException("Page " + p + " out of range (1.." + total + ")");
                outDoc.importPage(in.getPage(p - 1));
                PDPage imported = outDoc.getPage(outDoc.getNumberOfPages() - 1);
                int delta = (rotationDeltas != null && i < rotationDeltas.size()) ? rotationDeltas.get(i) : 0;
                if (delta != 0) imported.setRotation(((imported.getRotation() + delta) % 360 + 360) % 360);
            }
            outDoc.save(out);
        }
    }

    public static List<File> splitToFiles(File src, List<int[]> ranges1based, File outDir, String baseName) throws Exception {
        List<File> created = new ArrayList<>();
        try (PDDocument in = open(src)) {
            int total = in.getNumberOfPages();
            int idx = 1;
            for (int[] r : ranges1based) {
                int from = Math.max(1, Math.min(r[0], r[1]));
                int to = Math.min(total, Math.max(r[0], r[1]));
                if (from > to) continue;
                File dest = new File(outDir, baseName + "_part" + (idx++) + ".pdf");
                try (PDDocument part = new PDDocument()) {
                    for (int i = from; i <= to; i++) part.importPage(in.getPage(i - 1));
                    part.save(dest);
                }
                created.add(dest);
            }
        }
        return created;
    }

    // ---------------------------------------------------------------------
    // Content editing
    // ---------------------------------------------------------------------

    public static void addText(File src, int page1based, String text, float x, float y,
                               float size, int argbColor, File out) throws Exception {
        try (PDDocument d = open(src)) {
            PDPage page = d.getPage(page1based - 1);
            try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, size);
                cs.setNonStrokingColor((argbColor >> 16) & 0xFF, (argbColor >> 8) & 0xFF, argbColor & 0xFF);
                cs.newLineAtOffset(x, y);
                cs.showText(winAnsi(text));
                cs.endText();
            }
            d.save(out);
        }
    }

    public static void addImage(File src, int page1based, File image, float x, float y,
                                float w, float h, File out) throws Exception {
        try (PDDocument d = open(src)) {
            PDPage page = d.getPage(page1based - 1);
            PDImageXObject img = PDImageXObject.createFromFile(image.getAbsolutePath(), d);
            float dw = w > 0 ? w : img.getWidth();
            float dh = h > 0 ? h : img.getHeight();
            try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                cs.drawImage(img, x, y, dw, dh);
            }
            d.save(out);
        }
    }

    public static void applyStamps(File src, List<TextStamp> texts, List<ImageStamp> images,
                                   List<HighlightStamp> highlights, File out) throws Exception {
        try (PDDocument d = open(src)) {
            if (texts != null) {
                for (TextStamp t : texts) {
                    PDPage page = d.getPage(t.page - 1);
                    try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, t.size);
                        cs.setNonStrokingColor((t.color >> 16) & 0xFF, (t.color >> 8) & 0xFF, t.color & 0xFF);
                        cs.newLineAtOffset(t.x, t.y);
                        cs.showText(winAnsi(t.text));
                        cs.endText();
                    }
                }
            }
            if (images != null) {
                for (ImageStamp s : images) {
                    PDPage page = d.getPage(s.page - 1);
                    PDImageXObject img = PDImageXObject.createFromFile(s.image.getAbsolutePath(), d);
                    float dw = s.w > 0 ? s.w : img.getWidth();
                    float dh = s.h > 0 ? s.h : img.getHeight();
                    try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                        cs.drawImage(img, s.x, s.y, dw, dh);
                    }
                }
            }
            if (highlights != null) {
                for (HighlightStamp hs : highlights) {
                    PDPage page = d.getPage(hs.page - 1);
                    try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        gs.setNonStrokingAlphaConstant(0.35f);
                        cs.setGraphicsStateParameters(gs);
                        cs.setNonStrokingColor((hs.color >> 16) & 0xFF, (hs.color >> 8) & 0xFF, hs.color & 0xFF);
                        cs.addRect(hs.x, hs.y, hs.w, hs.h);
                        cs.fill();
                    }
                }
            }
            d.save(out);
        }
    }

    // ---------------------------------------------------------------------
    // Watermarks
    // ---------------------------------------------------------------------

    public static void watermarkText(File src, WatermarkOptions o, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            String safe = winAnsi(o.text);
            for (int i = 0; i < total; i++) {
                PDPage page = d.getPage(i);
                PDRectangle box = page.getCropBox();
                float size = o.fontSize > 0 ? o.fontSize
                        : Math.max(20f, Math.min(box.getWidth(), box.getHeight()) / 8f);
                PDType1Font font = pickFont(o.font, o.bold, o.italic);
                float w = font.getStringWidth(safe) / 1000f * size;
                float h = size;

                float x, y;
                if (o.randomPosition) {
                    Random rnd = new Random((i + 1) * 7919L + safe.hashCode());
                    float availW = Math.max(0f, box.getWidth() - w - 24f);
                    float availH = Math.max(0f, box.getHeight() - h - 24f);
                    x = box.getLowerLeftX() + 12f + (availW > 0 ? rnd.nextFloat() * availW : 0f);
                    y = box.getLowerLeftY() + 12f + (availH > 0 ? rnd.nextFloat() * availH : 0f);
                } else {
                    x = box.getLowerLeftX() + (box.getWidth() - w) / 2f;
                    y = box.getLowerLeftY() + (box.getHeight() - h) / 2f;
                }

                try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(Math.max(0.05f, Math.min(1f, o.opacity)));
                    cs.setGraphicsStateParameters(gs);
                    cs.setNonStrokingColor((o.color >> 16) & 0xFF, (o.color >> 8) & 0xFF, o.color & 0xFF);
                    cs.beginText();
                    cs.setFont(font, size);
                    if (o.angle != 0f) cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(o.angle), x, y));
                    else cs.newLineAtOffset(x, y);
                    cs.showText(safe);
                    cs.endText();
                }
            }
            d.save(out);
        }
    }

    /** Kept for convenience; centres a red diagonal text mark on every page. */
    public static void watermarkText(File src, String text, float fontSize, float opacity,
                                     int argbColor, float angleDeg, File out) throws Exception {
        WatermarkOptions o = new WatermarkOptions();
        o.text = text; o.fontSize = fontSize; o.opacity = opacity;
        o.color = argbColor; o.angle = angleDeg;
        watermarkText(src, o, out);
    }

    public static void watermarkImage(File src, File image, float opacity, float scale, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            PDImageXObject img = PDImageXObject.createFromFile(image.getAbsolutePath(), d);
            for (int i = 0; i < total; i++) {
                PDPage page = d.getPage(i);
                PDRectangle box = page.getCropBox();
                float targetW = box.getWidth() * (scale > 0 ? scale : 0.5f);
                float targetH = targetW * img.getHeight() / (float) img.getWidth();
                float x = box.getLowerLeftX() + (box.getWidth() - targetW) / 2f;
                float y = box.getLowerLeftY() + (box.getHeight() - targetH) / 2f;
                try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(opacity);
                    cs.setGraphicsStateParameters(gs);
                    cs.drawImage(img, x, y, targetW, targetH);
                }
            }
            d.save(out);
        }
    }

    // ---------------------------------------------------------------------
    // Page numbers
    // ---------------------------------------------------------------------

    public static void addPageNumbers(File src, String format, float fontSize, int argbColor, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = d.getPage(i);
                PDRectangle box = page.getCropBox();
                String label = format.replace("{n}", String.valueOf(i + 1)).replace("{total}", String.valueOf(total));
                label = winAnsi(label);
                float size = fontSize > 0 ? fontSize : 10f;
                float w = PDType1Font.HELVETICA.getStringWidth(label) / 1000f * size;
                float x = box.getLowerLeftX() + (box.getWidth() - w) / 2f;
                float y = box.getLowerLeftY() + 24f;
                try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, size);
                    cs.setNonStrokingColor((argbColor >> 16) & 0xFF, (argbColor >> 8) & 0xFF, argbColor & 0xFF);
                    cs.newLineAtOffset(x, y);
                    cs.showText(label);
                    cs.endText();
                }
            }
            d.save(out);
        }
    }

    // ---------------------------------------------------------------------
    // Security
    // ---------------------------------------------------------------------

    public static void encrypt(File src, String userPassword, String ownerPassword, File out) throws Exception {
        try (PDDocument d = open(src)) {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(true);
            ap.setCanModify(true);
            ap.setCanExtractContent(true);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    ownerPassword == null || ownerPassword.isEmpty() ? userPassword : ownerPassword,
                    userPassword == null ? "" : userPassword, ap);
            policy.setEncryptionKeyLength(128);
            policy.setPermissions(ap);
            d.protect(policy);
            d.save(out);
        }
    }

    /**
     * Remove protection. Works for files that open without a password (owner-only
     * protection) and for files whose user password is supplied.
     */
    public static void decrypt(File src, String password, File out) throws Exception {
        try (PDDocument d = open(src, password)) {
            d.setAllSecurityToBeRemoved(true);
            d.save(out);
        }
    }

    // ---------------------------------------------------------------------
    // Compression
    // ---------------------------------------------------------------------

    /** level: 0 = light, 1 = balanced, 2 = strong. */
    public static void compress(File src, File out, int level) throws Exception {
        int maxDim;
        float quality;
        switch (level) {
            case 0: maxDim = 2200; quality = 0.78f; break;
            case 2: maxDim = 1200; quality = 0.48f; break;
            default: maxDim = 1600; quality = 0.62f; break;
        }
        compress(src, out, maxDim, quality);
    }

    public static void compress(File src, File out) throws Exception {
        compress(src, out, 1600, 0.62f);
    }

    public static void compress(File src, File out, int maxDimension, float jpegQuality) throws Exception {
        try (PDDocument d = open(src)) {
            IdentityHashMap<COSBase, Boolean> seenImages = new IdentityHashMap<>();
            IdentityHashMap<COSBase, Boolean> seenResources = new IdentityHashMap<>();
            for (PDPage page : d.getPages()) {
                // embedded page thumbnails are pure dead weight
                page.getCOSObject().removeItem(COSName.THUMB);
                PDResources res = page.getResources();
                if (res != null) compressResources(d, res, seenImages, seenResources, maxDimension, jpegQuality);
            }
            // strip identifying metadata (small but free)
            PDDocumentInformation info = d.getDocumentInformation();
            info.setTitle(null);
            info.setSubject(null);
            info.setKeywords(null);
            d.save(out);
        }
    }

    private static void compressResources(PDDocument d, PDResources res,
                                          IdentityHashMap<COSBase, Boolean> seenImages,
                                          IdentityHashMap<COSBase, Boolean> seenResources,
                                          int maxDimension, float jpegQuality) {
        if (res == null) return;
        if (seenResources.put(res.getCOSObject(), Boolean.TRUE) != null) return;
        List<COSName> names = new ArrayList<>();
        for (COSName n : res.getXObjectNames()) names.add(n);
        for (COSName name : names) {
            PDXObject xo;
            try { xo = res.getXObject(name); } catch (Exception e) { continue; }
            if (xo instanceof PDFormXObject) {
                try { compressResources(d, ((PDFormXObject) xo).getResources(), seenImages, seenResources, maxDimension, jpegQuality); }
                catch (Exception ignore) { }
            } else if (xo instanceof PDImageXObject) {
                PDImageXObject img = (PDImageXObject) xo;
                if (seenImages.put(img.getCOSObject(), Boolean.TRUE) != null) continue;
                try {
                    Bitmap original = img.getImage();
                    if (original == null) continue;
                    boolean hasAlpha = original.hasAlpha();
                    int w = original.getWidth(), h = original.getHeight();
                    Bitmap scaled = null;
                    Bitmap toEncode = original;
                    if (maxDimension > 0 && Math.max(w, h) > maxDimension) {
                        float s = maxDimension / (float) Math.max(w, h);
                        scaled = Bitmap.createScaledBitmap(original, Math.max(1, Math.round(w * s)),
                                Math.max(1, Math.round(h * s)), true);
                        toEncode = scaled;
                    }
                    PDImageXObject replacement = hasAlpha
                            ? LosslessFactory.createFromImage(d, toEncode)
                            : JPEGFactory.createFromImage(d, toEncode, jpegQuality);
                    res.put(name, replacement);
                    if (scaled != null) scaled.recycle();
                    original.recycle();
                } catch (Throwable ignore) {
                    // leave the original image untouched
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    public static Map<String, String> getMetadata(File src) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        try (PDDocument d = open(src)) {
            PDDocumentInformation info = d.getDocumentInformation();
            m.put("title", nullToEmpty(info.getTitle()));
            m.put("author", nullToEmpty(info.getAuthor()));
            m.put("subject", nullToEmpty(info.getSubject()));
            m.put("keywords", nullToEmpty(info.getKeywords()));
            m.put("creator", nullToEmpty(info.getCreator()));
            m.put("producer", nullToEmpty(info.getProducer()));
            m.put("pages", String.valueOf(d.getNumberOfPages()));
        }
        return m;
    }

    public static void setMetadata(File src, String title, String author, String subject,
                                   String keywords, String creator, File out) throws Exception {
        try (PDDocument d = open(src)) {
            PDDocumentInformation info = d.getDocumentInformation();
            info.setTitle(emptyToNull(title));
            info.setAuthor(emptyToNull(author));
            info.setSubject(emptyToNull(subject));
            info.setKeywords(emptyToNull(keywords));
            info.setCreator(emptyToNull(creator));
            d.save(out);
        }
    }

    // ---------------------------------------------------------------------
    // AcroForm fields
    // ---------------------------------------------------------------------

    public static List<FormField> listFormFields(File src) throws Exception {
        List<FormField> out = new ArrayList<>();
        try (PDDocument d = open(src)) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            if (form == null) return out;
            for (PDField f : form.getFields()) collect(f, out);
        }
        return out;
    }

    private static void collect(PDField f, List<FormField> out) {
        if (f instanceof PDNonTerminalField) {
            for (PDField k : ((PDNonTerminalField) f).getChildren()) collect(k, out);
            return;
        }
        FormField ff = new FormField();
        ff.name = f.getFullyQualifiedName();
        ff.readOnly = f.isReadOnly();
        try { ff.value = f.getValueAsString(); } catch (Exception e) { ff.value = ""; }
        if (f instanceof PDTextField) {
            ff.type = "text";
        } else if (f instanceof PDCheckBox) {
            ff.type = "checkbox";
            try { ff.options.add(((PDCheckBox) f).getOnValue()); } catch (Exception ignore) { }
        } else if (f instanceof PDRadioButton) {
            ff.type = "radio";
            try { ff.options.addAll(((PDRadioButton) f).getExportValues()); } catch (Exception ignore) { }
        } else if (f instanceof PDComboBox) {
            ff.type = "combo";
            try { for (String o : ((PDComboBox) f).getOptionsExportValues()) ff.options.add(o); } catch (Exception ignore) { }
        } else if (f instanceof PDListBox) {
            ff.type = "list";
            try { for (String o : ((PDListBox) f).getOptionsExportValues()) ff.options.add(o); } catch (Exception ignore) { }
        } else {
            ff.type = "other";
        }
        out.add(ff);
    }

    public static void fillForm(File src, Map<String, String> values, File out) throws Exception {
        try (PDDocument d = open(src)) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            if (form == null) throw new IllegalArgumentException("This PDF has no fillable form fields");
            for (PDField f : form.getFields()) applyField(f, values);
            form.setNeedAppearances(true);
            d.save(out);
        }
    }

    private static void applyField(PDField f, Map<String, String> values) throws Exception {
        if (f instanceof PDNonTerminalField) {
            for (PDField k : ((PDNonTerminalField) f).getChildren()) applyField(k, values);
            return;
        }
        String name = f.getFullyQualifiedName();
        if (!values.containsKey(name)) return;
        String v = values.get(name);
        if (f instanceof PDCheckBox) {
            PDCheckBox cb = (PDCheckBox) f;
            if ("true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v)) cb.check();
            else cb.unCheck();
        } else {
            f.setValue(v == null ? "" : v);
        }
    }

    // ---------------------------------------------------------------------
    // Images <-> PDF
    // ---------------------------------------------------------------------

    /** One page per image, page sized to the image. Images are down-scaled to a sane bound. */
    public static void imagesToPdf(List<File> images, File out) throws Exception {
        imagesToPdf(images, out, 3000, false);
    }

    /**
     * @param maxDimension longest edge kept when decoding (0 = full size)
     * @param fitToA4    fit each image inside an A4 page instead of sizing the page to it
     */
    public static void imagesToPdf(List<File> images, File out, int maxDimension, boolean fitToA4) throws Exception {
        if (images == null || images.isEmpty()) throw new IllegalArgumentException("Pick at least one image");
        try (PDDocument d = new PDDocument()) {
            for (File image : images) {
                Bitmap bmp = decodeBounded(image, maxDimension);
                if (bmp == null) throw new IllegalArgumentException("Could not read image: " + image.getName());
                PDImageXObject img = bmp.hasAlpha()
                        ? LosslessFactory.createFromImage(d, bmp)
                        : JPEGFactory.createFromImage(d, bmp, 0.85f);

                PDRectangle pageSize;
                if (fitToA4) {
                    pageSize = PDRectangle.A4;
                } else {
                    float pw = Math.max(1f, bmp.getWidth());
                    float ph = Math.max(1f, bmp.getHeight());
                    // keep pages to a sensible size (max 2000pt on the long edge)
                    float longest = Math.max(pw, ph);
                    if (longest > 2000f) { float s = 2000f / longest; pw *= s; ph *= s; }
                    pageSize = new PDRectangle(pw, ph);
                }
                bmp.recycle();

                PDPage page = new PDPage(pageSize);
                d.addPage(page);
                float iw = img.getWidth(), ih = img.getHeight();
                float scale = Math.min(pageSize.getWidth() / iw, pageSize.getHeight() / ih);
                if (fitToA4) {
                    float dw = iw * scale, dh = ih * scale;
                    try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                        cs.drawImage(img, (pageSize.getWidth() - dw) / 2f, (pageSize.getHeight() - dh) / 2f, dw, dh);
                    }
                } else {
                    try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                        cs.drawImage(img, 0, 0, pageSize.getWidth(), pageSize.getHeight());
                    }
                }
            }
            d.save(out);
        }
    }

    private static Bitmap decodeBounded(File f, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        if (maxDimension > 0) {
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        if (bmp == null) return null;
        return bmp;
    }

    public static List<File> extractImages(File src, File outDir) throws Exception {
        List<File> written = new ArrayList<>();
        try (PDDocument d = open(src)) {
            IdentityHashMap<COSBase, Boolean> seen = new IdentityHashMap<>();
            int n = 0;
            for (PDPage page : d.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getXObjectNames()) {
                    PDXObject xo;
                    try { xo = res.getXObject(name); } catch (Exception e) { continue; }
                    if (!(xo instanceof PDImageXObject)) continue;
                    PDImageXObject img = (PDImageXObject) xo;
                    if (seen.put(img.getCOSObject(), Boolean.TRUE) != null) continue;
                    Bitmap bmp = img.getImage();
                    if (bmp == null) continue;
                    boolean alpha = bmp.hasAlpha();
                    File dest = new File(outDir, "image_" + (++n) + (alpha ? ".png" : ".jpg"));
                    try (OutputStream os = new FileOutputStream(dest)) {
                        bmp.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, os);
                    }
                    bmp.recycle();
                    written.add(dest);
                }
            }
        }
        return written;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static PDType1Font pickFont(String name, boolean bold, boolean italic) {
        String n = name == null ? "Helvetica" : name;
        if ("Times".equalsIgnoreCase(n)) {
            return bold && italic ? PDType1Font.TIMES_BOLD_ITALIC
                    : bold ? PDType1Font.TIMES_BOLD
                    : italic ? PDType1Font.TIMES_ITALIC
                    : PDType1Font.TIMES_ROMAN;
        }
        if ("Courier".equalsIgnoreCase(n)) {
            return bold && italic ? PDType1Font.COURIER_BOLD_OBLIQUE
                    : bold ? PDType1Font.COURIER_BOLD
                    : italic ? PDType1Font.COURIER_OBLIQUE
                    : PDType1Font.COURIER;
        }
        return bold && italic ? PDType1Font.HELVETICA_BOLD_OBLIQUE
                : bold ? PDType1Font.HELVETICA_BOLD
                : italic ? PDType1Font.HELVETICA_OBLIQUE
                : PDType1Font.HELVETICA;
    }

    /** Restrict a string to characters the standard-14 fonts can encode (WinAnsi-ish). */
    static String winAnsi(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') { b.append(' '); }
            else if (c >= 32 && c <= 126) { b.append(c); }
            else if (c >= 160 && c <= 255) { b.append(c); }
            else { b.append('?'); }
        }
        return b.toString();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String emptyToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s; }
}
