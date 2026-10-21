import com.vk062777.pdfsmith.engine.PdfEngine;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.form.*;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.*;

public class TestEngine {
  static int pass = 0, fail = 0;
  static File W = new File(System.getProperty("java.io.tmpdir"), "pdfsmith-engine-test");

  static void check(String name, boolean ok) {
    if (ok) { pass++; System.out.println("  PASS  " + name); }
    else { fail++; System.out.println("  FAIL  " + name); }
  }

  static File makePdf(String name, int pages, boolean withImage) throws Exception {
    PDDocument d = new PDDocument();
    for (int i = 1; i <= pages; i++) {
      PDPage p = new PDPage(PDRectangle.A4);
      d.addPage(p);
      try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 20);
        cs.newLineAtOffset(72, 700);
        cs.showText("PAGE_" + i + "_MARKER");
        cs.endText();
        if (withImage && i == 1) {
          PDImageXObject img = PDImageXObject.createFromFile(new File(W, "red.png").getAbsolutePath(), d);
          cs.drawImage(img, 72, 400, 200, 150);
        }
      }
    }
    File f = new File(W, name);
    d.save(f);
    d.close();
    return f;
  }

  static String textOf(File f, int page) throws Exception {
    try (PDDocument d = PDDocument.load(f)) {
      PDFTextStripper s = new PDFTextStripper();
      s.setStartPage(page); s.setEndPage(page);
      return s.getText(d);
    }
  }

  static int rotationOf(File f, int page) throws Exception {
    try (PDDocument d = PDDocument.load(f)) { return d.getPage(page - 1).getRotation(); }
  }

  public static void main(String[] args) throws Exception {
    W.mkdirs();
    // test images
    if (!new File(W, "red.png").exists()) {
      java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D g = bi.createGraphics(); g.setColor(java.awt.Color.RED); g.fillRect(0, 0, 600, 400); g.dispose();
      javax.imageio.ImageIO.write(bi, "png", new File(W, "red.png"));
    }
    if (!new File(W, "sig.png").exists()) {
      java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(400, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D g = bi.createGraphics(); g.setColor(java.awt.Color.BLUE); g.drawLine(10, 100, 380, 100); g.dispose();
      javax.imageio.ImageIO.write(bi, "png", new File(W, "sig.png"));
    }

    File a = makePdf("a.pdf", 3, true);
    File b = makePdf("b.pdf", 2, false);

    System.out.println("== info ==");
    check("pageCount(a)=3", PdfEngine.pageCount(a) == 3);
    check("isEncrypted(a)=false", !PdfEngine.isEncrypted(a));

    System.out.println("== merge ==");
    File merged = new File(W, "merged.pdf");
    PdfEngine.merge(Arrays.asList(a, b), merged);
    check("merged pages=5", PdfEngine.pageCount(merged) == 5);
    check("merged page1 has marker", textOf(merged, 1).contains("PAGE_1_MARKER"));

    System.out.println("== keep/remove/reorder ==");
    File kept = new File(W, "kept.pdf");
    PdfEngine.keepPages(a, Arrays.asList(3, 1), kept);
    check("kept pages=2", PdfEngine.pageCount(kept) == 2);
    check("kept order 3 then 1", textOf(kept, 1).contains("PAGE_3_MARKER") && textOf(kept, 2).contains("PAGE_1_MARKER"));
    File rem = new File(W, "rem.pdf");
    PdfEngine.removePages(a, Arrays.asList(2), rem);
    check("removed pages=2", PdfEngine.pageCount(rem) == 2);
    check("page2 gone", !textOf(rem, 2).contains("PAGE_2_MARKER"));
    File reo = new File(W, "reo.pdf");
    PdfEngine.reorderPages(a, Arrays.asList(2, 3, 1), reo);
    check("reorder ok", textOf(reo, 1).contains("PAGE_2_MARKER") && textOf(reo, 3).contains("PAGE_1_MARKER"));

    System.out.println("== rebuild ==");
    File rb = new File(W, "rb.pdf");
    PdfEngine.rebuild(a, Arrays.asList(3, 1, 2), Arrays.asList(0, 0, 0), rb);
    check("rebuild pages=3", PdfEngine.pageCount(rb) == 3);
    check("rebuild order 3,1,2", textOf(rb, 1).contains("PAGE_3_MARKER") && textOf(rb, 3).contains("PAGE_2_MARKER"));
    File rb2 = new File(W, "rb2.pdf");
    PdfEngine.rebuild(a, Arrays.asList(1, 2, 3), Arrays.asList(90, 0, 180), rb2);
    check("rebuild rotation p1=90", rotationOf(rb2, 1) == 90);
    check("rebuild rotation p3=180", rotationOf(rb2, 3) == 180);

    System.out.println("== rotate ==");
    File rot = new File(W, "rot.pdf");
    PdfEngine.rotatePages(a, Arrays.asList(1), 90, rot);
    check("page1 rotation=90", rotationOf(rot, 1) == 90);
    check("page2 rotation=0", rotationOf(rot, 2) == 0);

    System.out.println("== split ==");
    File spDir = new File(W, "split"); spDir.mkdirs();
    List<File> parts = PdfEngine.splitToFiles(a, Arrays.asList(new int[]{1, 2}, new int[]{3, 3}), spDir, "doc");
    check("split made 2 parts", parts.size() == 2);
    check("part1 pages=2", PdfEngine.pageCount(parts.get(0)) == 2);
    check("part2 pages=1", PdfEngine.pageCount(parts.get(1)) == 1);

    System.out.println("== text & image content ==");
    File txt = new File(W, "txt.pdf");
    PdfEngine.addText(a, 1, "HELLO_STAMP", 100, 100, 18, 0xFFFF0000, txt);
    check("addText present", textOf(txt, 1).contains("HELLO_STAMP"));
    File stamp = new File(W, "stamp.pdf");
    PdfEngine.applyStamps(a,
        Arrays.asList(new PdfEngine.TextStamp(2, 80, 200, 14, 0xFF000000, "STAMP_TEXT")),
        Arrays.asList(new PdfEngine.ImageStamp(1, 200, 300, 150, 100, new File(W, "sig.png"))),
        Arrays.asList(new PdfEngine.HighlightStamp(3, 70, 690, 300, 25, 0xFFFFFF00)),
        stamp);
    check("applyStamps text", textOf(stamp, 2).contains("STAMP_TEXT"));
    check("applyStamps valid", PdfEngine.pageCount(stamp) == 3);

    System.out.println("== watermark / page numbers ==");
    File wm = new File(W, "wm.pdf");
    PdfEngine.watermarkText(a, "CONFIDENTIAL", 48, 0.3f, 0xFFFF0000, 45, wm);
    check("watermark text present", textOf(wm, 1).replaceAll("\\s+", "").contains("CONFIDENTIAL"));
    File wmi = new File(W, "wmi.pdf");
    PdfEngine.watermarkImage(a, new File(W, "sig.png"), 0.25f, 0.6f, wmi);
    check("watermark image valid", PdfEngine.pageCount(wmi) == 3);
    File pn = new File(W, "pn.pdf");
    PdfEngine.addPageNumbers(a, "Page {n} of {total}", 10, 0xFF333333, pn);
    check("page number present", textOf(pn, 2).contains("Page 2 of 3"));

    System.out.println("== security ==");
    File enc = new File(W, "enc.pdf");
    PdfEngine.encrypt(a, "secret", "owner", enc);
    check("encrypted opens with pw", PdfEngine.pageCount(enc, "secret") == 3);
    boolean blocked = false;
    try { PdfEngine.pageCount(enc); } catch (Exception e) { blocked = true; }
    check("encrypted blocked without pw", blocked);
    File dec = new File(W, "dec.pdf");
    PdfEngine.decrypt(enc, "secret", dec);
    check("decrypted opens freely", PdfEngine.pageCount(dec) == 3);
    check("decrypted not encrypted", !PdfEngine.isEncrypted(dec));

    System.out.println("== compress ==");
    // Build an image-heavy PDF from a large photo and confirm real size reduction.
    File bigImg = new File(W, "big.jpg");
    if (!bigImg.exists()) {
      java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(2400, 1800, java.awt.image.BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D g = bi.createGraphics();
      for (int y = 0; y < 1800; y += 8) { g.setColor(new java.awt.Color((y * 7) % 255, (y * 3) % 255, (y * 11) % 255)); g.fillRect(0, y, 2400, 8); }
      g.dispose();
      javax.imageio.ImageIO.write(bi, "jpg", bigImg);
    }
    File big = new File(W, "big.pdf");
    PdfEngine.imagesToPdf(Arrays.asList(bigImg), big);
    File cmp = new File(W, "cmp.pdf");
    PdfEngine.compress(big, cmp);
    check("compress valid", PdfEngine.pageCount(cmp) == 1);
    check("compress actually shrinks a big image PDF", cmp.length() < big.length());
    System.out.println("     size " + big.length() + " -> " + cmp.length());

    System.out.println("== metadata ==");
    File meta = new File(W, "meta.pdf");
    PdfEngine.setMetadata(a, "My Title", "Author X", "Subject Y", "kw1 kw2", "PDFsmith", meta);
    Map<String, String> m = PdfEngine.getMetadata(meta);
    check("title set", "My Title".equals(m.get("title")));
    check("author set", "Author X".equals(m.get("author")));

    System.out.println("== images -> pdf ==");
    File ipdf = new File(W, "ipdf.pdf");
    PdfEngine.imagesToPdf(Arrays.asList(new File(W, "red.png"), new File(W, "sig.png")), ipdf);
    check("imagesToPdf pages=2", PdfEngine.pageCount(ipdf) == 2);

    System.out.println("== extract images ==");
    File exDir = new File(W, "extract"); exDir.mkdirs();
    List<File> ex = PdfEngine.extractImages(a, exDir);
    check("extracted >=1 image", ex.size() >= 1);
    check("extracted file exists", ex.get(0).exists() && ex.get(0).length() > 0);
    java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(ex.get(0));
    check("extracted image decodes", back != null && back.getWidth() > 0);

    System.out.println("== forms ==");
    File form = makeForm();
    List<PdfEngine.FormField> fields = PdfEngine.listFormFields(form);
    check("form has 1 field", fields.size() == 1);
    check("field name", fields.size() == 1 && "name".equals(fields.get(0).name));
    File filled = new File(W, "filled.pdf");
    Map<String, String> vals = new HashMap<>(); vals.put("name", "Ravi Kumar");
    PdfEngine.fillForm(form, vals, filled);
    List<PdfEngine.FormField> after = PdfEngine.listFormFields(filled);
    check("form value set", after.size() == 1 && "Ravi Kumar".equals(after.get(0).value));

    System.out.println();
    System.out.println("TOTAL: " + pass + " passed, " + fail + " failed");
    if (fail > 0) System.exit(1);
  }

  static File makeForm() throws Exception {
    PDDocument d = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    d.addPage(page);
    PDAcroForm form = new PDAcroForm(d);
    d.getDocumentCatalog().setAcroForm(form);
    form.setDefaultResources(new PDResources());
    PDTextField f = new PDTextField(form);
    f.setPartialName("name");
    form.getFields().add(f);
    PDAnnotationWidget w = f.getWidgets().get(0);
    w.setPage(page);
    w.setRectangle(new PDRectangle(50, 700, 220, 22));
    page.getAnnotations().add(w);
    File out = new File(W, "form.pdf");
    d.save(out);
    d.close();
    return out;
  }
}
