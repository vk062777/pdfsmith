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

    /** Never decode an image larger than this many pixels (guards against hostile files). */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    /** Bound recursion through nested form XObjects / form fields. */
    private static final int MAX_XOBJECT_DEPTH = 32;
    private static final int MAX_FIELD_DEPTH = 64;

    /** True if the file is encrypted at all (even if it opens without a password). */
    public static boolean isEncrypted(File f) {
        try (PDDocument d = PDDocument.load(f)) {
            return d.isEncrypted();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * True only when the file cannot be opened without a password.
     *
     * <p>A PDF that is not encrypted never mentions /Encrypt in its trailer, so the
     * common case is answered by reading the last few KB instead of parsing the whole
     * document — which is what makes opening files feel instant.</p>
     */
    public static boolean needsPassword(File f) {
        if (!trailerMentionsEncrypt(f)) return false;
        try (PDDocument d = PDDocument.load(f)) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean trailerMentionsEncrypt(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(8192L, len);
            byte[] buf = new byte[n];
            raf.seek(len - n);
            raf.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/Encrypt");
        } catch (Exception e) {
            return true; // be conservative
        }
    }

    /**
     * Crop one page (or every page) to a rectangle expressed in the page's
     * <em>displayed</em> frame: origin at the top-left, y downwards, in points.
     */
    public static void cropPages(File src, int page1based, float dx, float dy, float dw, float dh,
                                 boolean allPages, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                if (!allPages && i != page1based - 1) continue;
                PDPage page = d.getPage(i);
                PDRectangle box = page.getCropBox();
                float w = box.getWidth(), h = box.getHeight();
                int r = ((page.getRotation() % 360) + 360) % 360;

                float[][] corners = {{dx, dy}, {dx + dw, dy}, {dx, dy + dh}, {dx + dw, dy + dh}};
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (float[] c : corners) {
                    float xd = c[0], yd = c[1];
                    float px, py;
                    switch (r) {
                        case 90:  px = yd;      py = xd;      break;
                        case 180: px = w - xd;  py = yd;      break;
                        case 270: px = w - yd;  py = h - xd;  break;
                        default:  px = xd;      py = h - yd;  break;
                    }
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                }
                minX = Math.max(box.getLowerLeftX(), minX);
                minY = Math.max(box.getLowerLeftY(), minY);
                maxX = Math.min(box.getUpperRightX(), maxX);
                maxY = Math.min(box.getUpperRightY(), maxY);
                if (maxX - minX < 12f || maxY - minY < 12f) continue;
                PDRectangle crop = new PDRectangle(minX, minY, maxX - minX, maxY - minY);
                page.setCropBox(crop);
                page.setMediaBox(crop);
            }
            d.save(out);
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

    /** Page sizes as they appear on screen, i.e. taking /Rotate into account. */
    public static List<float[]> pageDisplaySizes(File f) throws Exception {
        List<float[]> out = new ArrayList<>();
        try (PDDocument d = open(f)) {
            for (PDPage p : d.getPages()) {
                PDRectangle r = p.getCropBox();
                int rot = ((p.getRotation() % 360) + 360) % 360;
                if (rot == 90 || rot == 270) out.add(new float[]{r.getHeight(), r.getWidth()});
                else out.add(new float[]{r.getWidth(), r.getHeight()});
            }
        }
        return out;
    }

    /**
     * Stamp a transparent overlay that was produced in the page's <em>displayed</em>
     * frame (after /Rotate) onto each page, so annotations land where the user saw them.
     */
    public static void stampDisplayRasters(File src, List<Integer> pages1based,
                                          List<File> rasters, File out) throws Exception {
        if (pages1based.size() != rasters.size()) {
            throw new IllegalArgumentException("pages and rasters must line up");
        }
        try (PDDocument d = open(src)) {
            for (int i = 0; i < pages1based.size(); i++) {
                File raster = rasters.get(i);
                if (raster == null || !raster.exists()) continue;
                PDPage page = d.getPage(pages1based.get(i) - 1);
                PDRectangle box = page.getCropBox();
                float w = box.getWidth(), h = box.getHeight();
                int rot = ((page.getRotation() % 360) + 360) % 360;
                float wd = (rot == 90 || rot == 270) ? h : w;
                float hd = (rot == 90 || rot == 270) ? w : h;
                PDImageXObject img = PDImageXObject.createFromFile(raster.getAbsolutePath(), d);
                Matrix m;
                switch (rot) {
                    case 90:  m = new Matrix(0, 1, -1, 0, w, 0); break;
                    case 180: m = new Matrix(-1, 0, 0, -1, w, h); break;
                    case 270: m = new Matrix(0, -1, 1, 0, 0, h); break;
                    default:  m = new Matrix(1, 0, 0, 1, 0, 0); break;
                }
                try (PDPageContentStream cs = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    cs.saveGraphicsState();
                    cs.transform(m);
                    cs.drawImage(img, 0, 0, wd, hd);
                    cs.restoreGraphicsState();
                }
            }
            d.save(out);
        }
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
                // Ignore anything out of range instead of failing the whole operation.
                if (p < 1 || p > total) continue;
                outDoc.importPage(in.getPage(p - 1));
            }
            if (outDoc.getNumberOfPages() == 0) {
                throw new IllegalArgumentException("No valid pages were selected");
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
        addPageNumbers(src, format, fontSize, argbColor, 1f, out);
    }

    public static void addPageNumbers(File src, String format, float fontSize, int argbColor,
                                      float opacity, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            float alpha = Math.max(0.05f, Math.min(1f, opacity));
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
                    if (alpha < 1f) {
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        gs.setNonStrokingAlphaConstant(alpha);
                        cs.setGraphicsStateParameters(gs);
                    }
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
            // Reading and printing stay allowed; changing the document does not.
            ap.setCanPrint(true);
            ap.setCanPrintDegraded(true);
            ap.setCanExtractContent(true);
            ap.setCanExtractForAccessibility(true);
            ap.setCanModify(false);
            ap.setCanModifyAnnotations(false);
            ap.setCanFillInForm(false);
            ap.setCanAssembleDocument(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    ownerPassword == null || ownerPassword.isEmpty() ? userPassword : ownerPassword,
                    userPassword == null ? "" : userPassword, ap);
            policy.setEncryptionKeyLength(256);
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
                if (res != null) compressResources(d, res, seenImages, seenResources, maxDimension, jpegQuality, 0);
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
                                          int maxDimension, float jpegQuality, int depth) {
        if (res == null) return;
        if (depth > MAX_XOBJECT_DEPTH) return;
        if (seenResources.put(res.getCOSObject(), Boolean.TRUE) != null) return;
        List<COSName> names = new ArrayList<>();
        for (COSName n : res.getXObjectNames()) names.add(n);
        for (COSName name : names) {
            PDXObject xo;
            try { xo = res.getXObject(name); } catch (Exception e) { continue; }
            if (xo instanceof PDFormXObject) {
                try { compressResources(d, ((PDFormXObject) xo).getResources(), seenImages, seenResources, maxDimension, jpegQuality, depth + 1); }
                catch (Exception ignore) { }
            } else if (xo instanceof PDImageXObject) {
                PDImageXObject img = (PDImageXObject) xo;
                if (seenImages.put(img.getCOSObject(), Boolean.TRUE) != null) continue;
                try {
                    if (!safeToDecode(img)) continue;
                    Bitmap original;
                    try { original = img.getImage(); } catch (OutOfMemoryError | Exception e) { continue; }
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

    /** All editable document properties. */
    public static final class MetadataInfo {
        public String title, author, subject, keywords, creator, producer;
        /** yyyy-MM-dd HH:mm, or null to leave unchanged. */
        public String creationDate, modDate;
    }

    private static final java.text.SimpleDateFormat META_DATE =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);

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
            m.put("creationDate", info.getCreationDate() == null ? "" : META_DATE.format(info.getCreationDate().getTime()));
            m.put("modDate", info.getModificationDate() == null ? "" : META_DATE.format(info.getModificationDate().getTime()));
            m.put("pages", String.valueOf(d.getNumberOfPages()));
        }
        return m;
    }

    public static void setMetadata(File src, MetadataInfo in, File out) throws Exception {
        try (PDDocument d = open(src)) {
            PDDocumentInformation info = d.getDocumentInformation();
            info.setTitle(emptyToNull(in.title));
            info.setAuthor(emptyToNull(in.author));
            info.setSubject(emptyToNull(in.subject));
            info.setKeywords(emptyToNull(in.keywords));
            info.setCreator(emptyToNull(in.creator));
            info.setProducer(emptyToNull(in.producer));
            java.util.Calendar c = parseDate(in.creationDate);
            if (c != null) info.setCreationDate(c);
            java.util.Calendar m = parseDate(in.modDate);
            if (m != null) info.setModificationDate(m);
            d.save(out);
        }
    }

    /** Remove every document property, including the XMP metadata stream. */
    public static void clearMetadata(File src, File out) throws Exception {
        try (PDDocument d = open(src)) {
            d.setDocumentInformation(new PDDocumentInformation());
            d.getDocumentCatalog().getCOSObject().removeItem(COSName.METADATA);
            d.save(out);
        }
    }

    private static java.util.Calendar parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(META_DATE.parse(s.trim()));
            return c;
        } catch (Exception e) {
            return null;
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
        collect(f, out, 0);
    }

    private static void collect(PDField f, List<FormField> out, int depth) {
        if (depth > MAX_FIELD_DEPTH) return;
        if (f instanceof PDNonTerminalField) {
            for (PDField k : ((PDNonTerminalField) f).getChildren()) collect(k, out, depth + 1);
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

    // ------------------------------------------------------------------
    // OCR text layer
    // ------------------------------------------------------------------

    /** One recognised word, in the pixel coordinates of the raster the OCR ran on
     *  (origin at the top-left). */
    public static final class OcrWord {
        public final String text;
        public final float x, y, w, h;

        public OcrWord(String text, float x, float y, float w, float h) {
            this.text = text;
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    /** Everything recognised on one page. */
    public static final class PageText {
        public final int page;      // 1-based
        public final int imgW, imgH;
        public final List<OcrWord> words;

        public PageText(int page, int imgW, int imgH, List<OcrWord> words) {
            this.page = page; this.imgW = imgW; this.imgH = imgH; this.words = words;
        }
    }

    /** Where the text baseline sits inside a word box (fraction from the top). */
    private static final float BASELINE_FRACTION = 0.82f;
    /** Glyph size relative to the box height. */
    private static final float FONT_HEIGHT_FRACTION = 0.92f;

    /**
     * Add an invisible, selectable text layer built from OCR results, so a scanned
     * PDF becomes searchable and its text can be copied. The page looks identical:
     * the glyphs are drawn in rendering mode 3 ("neither fill nor stroke").
     */
    public static void addTextLayer(File src, List<PageText> pages, File out) throws Exception {
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            for (PageText pt : pages) {
                if (pt == null || pt.page < 1 || pt.page > total) continue;
                if (pt.words.isEmpty() || pt.imgW <= 0 || pt.imgH <= 0) continue;
                PDPage page = d.getPage(pt.page - 1);
                PDRectangle box = page.getCropBox();
                float w = box.getWidth(), h = box.getHeight();
                int r = ((page.getRotation() % 360) + 360) % 360;
                // Displayed page size (the raster was produced in the displayed frame).
                float dw = (r == 90 || r == 270) ? h : w;
                float dh = (r == 90 || r == 270) ? w : h;

                try (PDPageContentStream cs =
                             new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
                    cs.beginText();
                    for (OcrWord word : pt.words) {
                        String txt = winAnsi(word.text);
                        if (txt == null || txt.trim().isEmpty()) continue;
                        float xd = word.x / pt.imgW * dw;
                        float yd = (word.y + word.h * BASELINE_FRACTION) / pt.imgH * dh;
                        float hd = word.h / pt.imgH * dh;
                        float targetW = word.w / pt.imgW * dw;
                        // Derive the size from the box WIDTH so the placed run matches the
                        // ink width (that is what selection and highlighting follow). Fall
                        // back to the box height, and keep the result sane.
                        float emWidth = PDType1Font.HELVETICA.getStringWidth(txt) / 1000f;
                        float byHeight = hd * FONT_HEIGHT_FRACTION;
                        float size;
                        if (emWidth > 0.02f && targetW > 0.5f) {
                            size = targetW / emWidth;
                            size = Math.max(byHeight * 0.5f, Math.min(byHeight * 1.6f, size));
                        } else {
                            size = byHeight;
                        }
                        size = Math.max(1f, size);
                        // displayed point -> page point (same mapping the cropper uses)
                        float px, py;
                        switch (r) {
                            case 90:  px = yd;      py = xd;      break;
                            case 180: px = w - xd;  py = yd;      break;
                            case 270: px = w - yd;  py = h - xd;  break;
                            default:  px = xd;      py = h - yd;  break;
                        }
                        cs.setFont(PDType1Font.HELVETICA, size);
                        // rotate the run so it reads upright once the page /Rotate is applied
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(r), px, py));
                        cs.showText(txt);
                    }
                    cs.endText();
                }
            }
            d.save(out);
        }
    }

    // ------------------------------------------------------------------
    // Text search and hyperlinks
    // ------------------------------------------------------------------

    /** A text match, located on the page in DISPLAY points (origin top-left, y down),
     *  i.e. the same frame the page is rendered in. */
    public static final class Match {
        public final int page;          // 1-based
        public final float x, y, w, h;
        public final String context;

        public Match(int page, float x, float y, float w, float h, String context) {
            this.page = page; this.x = x; this.y = y; this.w = w; this.h = h;
            this.context = context;
        }
    }

    /** A detected or added hyperlink. */
    public static final class FoundLink {
        public final int page;
        public final float x, y, w, h;   // display points
        public final String url;

        public FoundLink(int page, float x, float y, float w, float h, String url) {
            this.page = page; this.x = x; this.y = y; this.w = w; this.h = h;
            this.url = url;
        }
    }

    /**
     * Walks the WHOLE document once, keeping every glyph with its position and the
     * page it belongs to. Doing this in a single pass is what makes search fast:
     * re-parsing per page turned a 100-page search into a hundred parses.
     */
    private static final class PositionedText extends com.tom_roush.pdfbox.text.PDFTextStripper {
        final List<String> chars = new ArrayList<>();
        final List<com.tom_roush.pdfbox.text.TextPosition> pos = new ArrayList<>();
        final List<Integer> page = new ArrayList<>();

        PositionedText() throws java.io.IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text,
                                   List<com.tom_roush.pdfbox.text.TextPosition> tps) {
            if (tps == null) return;
            int pg = getCurrentPageNo();
            for (com.tom_roush.pdfbox.text.TextPosition tp : tps) {
                String u = tp.getUnicode();
                if (u == null || u.isEmpty()) continue;
                chars.add(u);
                pos.add(tp);
                page.add(pg);
            }
            chars.add(" ");
            pos.add(null);          // marks a word break
            page.add(pg);
        }

        String text() {
            StringBuilder b = new StringBuilder(chars.size());
            for (String c : chars) b.append(c);
            return b.toString();
        }

        /** The page of the first real glyph at or after [from]. */
        int pageAt(int from) {
            for (int k = Math.max(0, from); k < pos.size(); k++) {
                if (pos.get(k) != null) return page.get(k);
            }
            return -1;
        }
    }

    private static PositionedText positionedText(PDDocument d) throws Exception {
        PositionedText pt = new PositionedText();
        pt.setSortByPosition(true);
        pt.getText(d);          // one pass over the whole document
        return pt;
    }

    /** Crop-box size and /Rotate for every page, so results can be mapped cheaply. */
    private static float[][] pageGeom(PDDocument d) throws Exception {
        int n = d.getNumberOfPages();
        float[][] g = new float[n + 1][3];
        for (int p = 1; p <= n; p++) {
            PDPage pg = d.getPage(p - 1);
            PDRectangle cb = pg.getCropBox();
            g[p][0] = cb.getWidth();
            g[p][1] = cb.getHeight();
            g[p][2] = ((pg.getRotation() % 360) + 360) % 360;
        }
        return g;
    }

    /** Union of the glyph boxes in [from, to) as (x, yTop, w, h) in display points. */
    private static float[] boxOf(PositionedText pt, int from, int to) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int seen = 0;
        for (int k = from; k < to && k < pt.pos.size(); k++) {
            com.tom_roush.pdfbox.text.TextPosition tp = pt.pos.get(k);
            if (tp == null) continue;
            float x = tp.getXDirAdj();
            float h = tp.getHeightDir();
            // getYDirAdj() is the baseline; the ink sits above it
            float yTop = tp.getYDirAdj() - h;
            minX = Math.min(minX, x);
            minY = Math.min(minY, yTop);
            maxX = Math.max(maxX, x + tp.getWidthDirAdj());
            maxY = Math.max(maxY, yTop + h);
            seen++;
        }
        if (seen == 0) return null;
        return new float[]{minX, minY, maxX - minX, maxY - minY};
    }

    /** PDFTextStripper reports unrotated page coordinates; the UI works in the
     *  displayed frame, so convert. Both are y-down. */
    private static float[] unrotatedToDisplay(float w, float h, int r,
                                              float x, float y, float bw, float bh) {
        switch (r) {
            case 90:  return new float[]{h - y - bh, x, bh, bw};
            case 180: return new float[]{w - x - bw, h - y - bh, bw, bh};
            case 270: return new float[]{y, w - x - bw, bh, bw};
            default:  return new float[]{x, y, bw, bh};
        }
    }

    /** Find every occurrence of [query]. Empty query returns nothing. */
    public static List<Match> search(File src, String query, boolean matchCase) throws Exception {
        List<Match> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        try (PDDocument d = open(src)) {
            PositionedText pt = positionedText(d);
            float[][] g = pageGeom(d);
            String text = pt.text();
            String hay = matchCase ? text : text.toLowerCase();
            String needle = matchCase ? query : query.toLowerCase();
            int from = 0;
            while (true) {
                int i = hay.indexOf(needle, from);
                if (i < 0) break;
                int pg = pt.pageAt(i);
                float[] b = boxOf(pt, i, i + needle.length());
                if (b != null && pg >= 1 && pg < g.length) {
                    float[] db = unrotatedToDisplay(g[pg][0], g[pg][1], (int) g[pg][2],
                            b[0], b[1], b[2], b[3]);
                    int cs = Math.max(0, i - 28);
                    int ce = Math.min(text.length(), i + needle.length() + 28);
                    out.add(new Match(pg, db[0], db[1], db[2], db[3],
                            text.substring(cs, ce).replace('\n', ' ').trim()));
                }
                from = i + Math.max(1, needle.length());
            }
        }
        return out;
    }

    /** URL / e-mail shapes worth turning into links. */
    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile(
            "(https?://[^\\s<>\"')]+|www\\.[^\\s<>\"')]+|"
            + "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /** Every URL / e-mail address found in the document's text, with its position. */
    public static List<FoundLink> findLinks(File src) throws Exception {
        List<FoundLink> out = new ArrayList<>();
        try (PDDocument d = open(src)) {
            PositionedText pt = positionedText(d);
            float[][] g = pageGeom(d);
            String text = pt.text();
            java.util.regex.Matcher m = URL_PATTERN.matcher(text);
            while (m.find()) {
                String raw = m.group();
                String url = raw.replaceAll("[.,;:!?]+$", "");
                if (url.length() < 4) continue;
                int pg = pt.pageAt(m.start());
                if (pg < 1 || pg >= g.length) continue;
                float[] b = boxOf(pt, m.start(), m.start() + url.length());
                if (b == null) continue;
                float[] db = unrotatedToDisplay(g[pg][0], g[pg][1], (int) g[pg][2],
                        b[0], b[1], b[2], b[3]);
                // a link must cover its own text, never swallow the page
                if (db[2] * db[3] > g[pg][0] * g[pg][1] * 0.25f) continue;
                out.add(new FoundLink(pg, db[0], db[1], db[2], db[3], url));
            }
        }
        return out;
    }

    /** Turn a display-frame box into a page rectangle, honouring /Rotate. */
    private static PDRectangle displayBoxToPage(PDRectangle crop, int r,
                                                float xd, float yd, float wd, float hd) {
        float w = crop.getWidth(), h = crop.getHeight();
        float[][] corners = {{xd, yd}, {xd + wd, yd}, {xd, yd + hd}, {xd + wd, yd + hd}};
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] c : corners) {
            float px, py;
            switch (r) {
                case 90:  px = c[1];      py = c[0];      break;
                case 180: px = w - c[0];  py = c[1];      break;
                case 270: px = w - c[1];  py = h - c[0];  break;
                default:  px = c[0];      py = h - c[1];  break;
            }
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /** Attach real link annotations for the given links. Returns how many were added. */
    public static int addLinks(File src, List<FoundLink> links, File out) throws Exception {
        int added = 0;
        try (PDDocument d = open(src)) {
            int total = d.getNumberOfPages();
            for (FoundLink l : links) {
                if (l == null || l.page < 1 || l.page > total) continue;
                String url = normaliseUrl(l.url);
                if (url == null) continue;
                PDPage page = d.getPage(l.page - 1);
                PDRectangle crop = page.getCropBox();
                int r = ((page.getRotation() % 360) + 360) % 360;
                // never let a link cover a large part of the page
                if (l.w * l.h > crop.getWidth() * crop.getHeight() * 0.25f) continue;
                PDRectangle rect = displayBoxToPage(crop, r, l.x, l.y, l.w, l.h);
                // a link needs a little padding to be comfortably tappable
                rect.setLowerLeftX(rect.getLowerLeftX() - 1f);
                rect.setLowerLeftY(rect.getLowerLeftY() - 1f);
                rect.setUpperRightX(rect.getUpperRightX() + 1f);
                rect.setUpperRightY(rect.getUpperRightY() + 1f);

                com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link =
                        new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
                link.setRectangle(rect);
                link.setBorderStyle(new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary());
                link.getBorderStyle().setWidth(0f);
                com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI action =
                        new com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI();
                action.setURI(url);
                link.setAction(action);
                page.getAnnotations().add(link);
                added++;
            }
            d.save(out);
        }
        return added;
    }

    /** Add a single link over a rectangle given in display points. */
    public static void addLink(File src, int page1, float xd, float yd, float wd, float hd,
                               String url, File out) throws Exception {
        String u = normaliseUrl(url);
        if (u == null) throw new IllegalArgumentException("Not a valid link");
        FoundLink l = new FoundLink(page1, xd, yd, wd, hd, u);
        addLinks(src, java.util.Collections.singletonList(l), out);
    }

    /**
     * Draw a VISIBLE link icon at a spot in the displayed frame and put the link
     * exactly on the icon, so only a tap on the icon opens it.
     */
    public static void addLinkIcon(File src, int page1, float xd, float yd, float size,
                                   File iconPng, String url, File out) throws Exception {
        String u = normaliseUrl(url);
        if (u == null) throw new IllegalArgumentException("Not a valid link");
        try (PDDocument d = open(src)) {
            if (page1 < 1 || page1 > d.getNumberOfPages()) {
                throw new IllegalArgumentException("No such page");
            }
            PDPage page = d.getPage(page1 - 1);
            PDRectangle crop = page.getCropBox();
            float w = crop.getWidth(), h = crop.getHeight();
            int r = ((page.getRotation() % 360) + 360) % 360;
            float dw = (r == 90 || r == 270) ? h : w;
            float dh = (r == 90 || r == 270) ? w : h;

            float s = Math.max(10f, Math.min(size, Math.min(dw, dh) * 0.5f));
            xd = Math.max(0f, Math.min(xd, dw - s));
            yd = Math.max(0f, Math.min(yd, dh - s));

            if (iconPng != null && iconPng.exists()) {
                PDImageXObject icon = PDImageXObject.createFromFile(iconPng.getAbsolutePath(), d);
                Matrix m;
                switch (r) {
                    case 90:  m = new Matrix(0, 1, -1, 0, w, 0); break;
                    case 180: m = new Matrix(-1, 0, 0, -1, w, h); break;
                    case 270: m = new Matrix(0, -1, 1, 0, 0, h); break;
                    default:  m = new Matrix(1, 0, 0, 1, 0, 0); break;
                }
                try (PDPageContentStream cs =
                             new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                    cs.saveGraphicsState();
                    cs.transform(m);
                    cs.drawImage(icon, xd, dh - yd - s, s, s);
                    cs.restoreGraphicsState();
                }
            }

            PDRectangle rect = displayBoxToPage(crop, r, xd, yd, s, s);
            com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link =
                    new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            link.setRectangle(rect);
            link.setBorderStyle(new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary());
            link.getBorderStyle().setWidth(0f);
            com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI action =
                    new com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI();
            action.setURI(u);
            link.setAction(action);
            page.getAnnotations().add(link);
            d.save(out);
        }
    }

    /** The standard PDF fonts offered for custom link text. */
    public static final String[] LINK_FONTS = {
            "Helvetica", "Helvetica Bold", "Times", "Times Bold", "Courier"
    };

    private static PDType1Font pickFont(String name) {
        if (name == null) return PDType1Font.HELVETICA;
        switch (name) {
            case "Helvetica Bold": return PDType1Font.HELVETICA_BOLD;
            case "Times":          return PDType1Font.TIMES_ROMAN;
            case "Times Bold":     return PDType1Font.TIMES_BOLD;
            case "Courier":        return PDType1Font.COURIER;
            default:               return PDType1Font.HELVETICA;
        }
    }

    /**
     * Write VISIBLE custom text on a page in the chosen font, size and colour, and
     * make that text a link. Tapping the text opens the address; tapping anywhere
     * else does nothing.
     */
    public static void addTextLink(File src, int page1, float xd, float yd, float size,
                                   String text, String fontName, int argb,
                                   String url, File out) throws Exception {
        String u = normaliseUrl(url);
        if (u == null) throw new IllegalArgumentException("Not a valid link");
        String safe = winAnsi(text);
        if (safe == null || safe.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter the text to show");
        }
        float sz = Math.max(6f, Math.min(size, 144f));
        try (PDDocument d = open(src)) {
            if (page1 < 1 || page1 > d.getNumberOfPages()) {
                throw new IllegalArgumentException("No such page");
            }
            PDPage page = d.getPage(page1 - 1);
            PDRectangle crop = page.getCropBox();
            float w = crop.getWidth(), h = crop.getHeight();
            int r = ((page.getRotation() % 360) + 360) % 360;
            float dw = (r == 90 || r == 270) ? h : w;
            float dh = (r == 90 || r == 270) ? w : h;

            PDType1Font font = pickFont(fontName);
            float tw = font.getStringWidth(safe) / 1000f * sz;
            float th = sz * 1.3f;
            xd = Math.max(0f, Math.min(xd, Math.max(0f, dw - tw)));
            yd = Math.max(0f, Math.min(yd, Math.max(0f, dh - th)));
            float baseY = yd + sz * 0.82f;

            float px, py;
            switch (r) {
                case 90:  px = baseY;      py = xd;         break;
                case 180: px = w - xd;     py = baseY;      break;
                case 270: px = w - baseY;  py = h - xd;     break;
                default:  px = xd;         py = h - baseY;  break;
            }
            try (PDPageContentStream cs =
                         new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(font, sz);
                cs.setNonStrokingColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(r), px, py));
                cs.showText(safe);
                cs.endText();
            }

            PDRectangle rect = displayBoxToPage(crop, r, xd, yd, tw, th);
            com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link =
                    new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            link.setRectangle(rect);
            link.setBorderStyle(new com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary());
            link.getBorderStyle().setWidth(0f);
            com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI action =
                    new com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI();
            action.setURI(u);
            link.setAction(action);
            page.getAnnotations().add(link);
            d.save(out);
        }
    }

    /** "example.com" -> "https://example.com"; mail addresses become mailto:. */
    public static String normaliseUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s;
        if (s.contains("@") && !s.contains(" ")) return "mailto:" + s;
        if (lower.startsWith("www.")) return "https://" + s;
        if (lower.matches("[a-z0-9.-]+\\.[a-z]{2,}(/.*)?")) return "https://" + s;
        return null;
    }

    /** One page of a document built from scratch. */
    @SuppressWarnings("unused")
    public static final class PageSpec {
        public float width, height;
        public int bgColor = 0xFFFFFFFF;
        /** Transparent overlay (annotations/text) drawn on top of the background, or null. */
        public File raster;

        public PageSpec(float width, float height, int bgColor, File raster) {
            this.width = width; this.height = height; this.bgColor = bgColor; this.raster = raster;
        }
    }

    /** Build a PDF from a list of pages (background colour + optional transparent overlay). */
    public static void createPdf(List<PageSpec> pages, File out) throws Exception {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("Add at least one page");
        try (PDDocument d = new PDDocument()) {
            for (PageSpec p : pages) {
                PDPage page = new PDPage(new PDRectangle(p.width, p.height));
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.setNonStrokingColor((p.bgColor >> 16) & 0xFF, (p.bgColor >> 8) & 0xFF, p.bgColor & 0xFF);
                    cs.addRect(0, 0, p.width, p.height);
                    cs.fill();
                    if (p.raster != null && p.raster.exists()) {
                        PDImageXObject img = PDImageXObject.createFromFile(p.raster.getAbsolutePath(), d);
                        cs.drawImage(img, 0, 0, p.width, p.height);
                    }
                }
            }
            d.save(out);
        }
    }

    /** Refuse to decode an image the file claims is absurdly large. */
    private static boolean safeToDecode(PDImageXObject img) {
        try {
            long w = img.getWidth(), h = img.getHeight();
            return w > 0 && h > 0 && w * h <= MAX_IMAGE_PIXELS;
        } catch (Exception e) {
            return false;
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
        return extractImages(src, outDir, null);
    }

    /** Extract images, optionally limited to a set of 1-based pages (null = all pages). */
    public static List<File> extractImages(File src, File outDir, List<Integer> pages1based) throws Exception {
        List<File> written = new ArrayList<>();
        try (PDDocument d = open(src)) {
            IdentityHashMap<COSBase, Boolean> seen = new IdentityHashMap<>();
            int n = 0;
            int index = 0;
            Set<Integer> only = pages1based == null ? null : new HashSet<>(pages1based);
            for (PDPage page : d.getPages()) {
                index++;
                if (only != null && !only.contains(index)) continue;
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getXObjectNames()) {
                    PDXObject xo;
                    try { xo = res.getXObject(name); } catch (Exception e) { continue; }
                    if (!(xo instanceof PDImageXObject)) continue;
                    PDImageXObject img = (PDImageXObject) xo;
                    if (seen.put(img.getCOSObject(), Boolean.TRUE) != null) continue;
                    if (!safeToDecode(img)) continue;
                    Bitmap bmp;
                    try { bmp = img.getImage(); } catch (OutOfMemoryError | Exception e) { continue; }
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
