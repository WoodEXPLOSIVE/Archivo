package at.woodexplosive.learnarchiver.scan;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Baut aus JPEG-Seiten ein PDF – ohne Bibliothek und OHNE Neukomprimierung:
 * PDF kann JPEG-Daten direkt einbetten (Filter /DCTDecode). Die Dateien bleiben dadurch klein
 * und es geht schnell. android.graphics.pdf.PdfDocument würde die Bilder dagegen verlustfrei
 * neu kodieren und das PDF um ein Vielfaches aufblähen.
 * <p>
 * Seitengröße = Pixel / dpi * 72 (PDF rechnet in Punkt = 1/72 Zoll) → Druckgröße stimmt.
 * <p>
 * Drehen ist ebenfalls verlustfrei: Die Seite bekommt nur den Eintrag /Rotate, das Bild bleibt unverändert.
 */
public final class JpegPdfWriter {

    /** Eine Seite: JPEG-Datei, Auflösung und Drehung im Uhrzeigersinn (0, 90, 180, 270). Unveränderlich. */
    public static class Page {
        final File jpeg;
        final int dpi;
        final int rotation;

        public Page(File jpeg, int dpi) {
            this(jpeg, dpi, 0);
        }

        public Page(File jpeg, int dpi, int rotation) {
            this.jpeg = jpeg;
            this.dpi = dpi;
            this.rotation = ((rotation % 360) + 360) % 360;
        }

        public File jpegFile() {
            return jpeg;
        }

        public int rotation() {
            return rotation;
        }

        /** Dieselbe Seite, um 90° weiter im Uhrzeigersinn gedreht */
        public Page rotatedClockwise() {
            return new Page(jpeg, dpi, rotation + 90);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Page)) return false;
            Page p = (Page) o;
            return jpeg.equals(p.jpeg) && dpi == p.dpi && rotation == p.rotation;
        }

        @Override
        public int hashCode() {
            return jpeg.hashCode() * 31 + rotation;
        }
    }

    private JpegPdfWriter() {}

    public static void write(List<Page> pages, File out) throws IOException {
        if (pages.isEmpty()) throw new IOException("Keine Seiten");

        try (CountingStream os = new CountingStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            List<Long> offsets = new ArrayList<>();
            int n = pages.size();

            os.ascii("%PDF-1.4\n");
            os.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});   // "Binärdatei"-Markierung

            // Objekt 1: Katalog, Objekt 2: Seitenbaum, danach pro Seite 3 Objekte (Seite, Inhalt, Bild)
            offsets.add(os.count);
            os.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(pageObj(i)).append(" 0 R ");
            offsets.add(os.count);
            os.ascii("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n");

            for (int i = 0; i < n; i++) {
                Page page = pages.get(i);
                JpegInfo info = readJpegInfo(page.jpeg);
                float w = info.width * 72f / page.dpi;
                float h = info.height * 72f / page.dpi;

                offsets.add(os.count);
                os.ascii(pageObj(i) + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(w) + " " + f(h) + "]"
                        + (page.rotation != 0 ? " /Rotate " + page.rotation : "")
                        + " /Contents " + (pageObj(i) + 1) + " 0 R"
                        + " /Resources << /XObject << /Im0 " + (pageObj(i) + 2) + " 0 R >> >> >>\nendobj\n");

                // Inhalt: Bild auf ganze Seite skalieren und zeichnen
                String content = "q " + f(w) + " 0 0 " + f(h) + " 0 0 cm /Im0 Do Q";
                offsets.add(os.count);
                os.ascii((pageObj(i) + 1) + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                        + content + "\nendstream\nendobj\n");

                offsets.add(os.count);
                os.ascii((pageObj(i) + 2) + " 0 obj\n<< /Type /XObject /Subtype /Image"
                        + " /Width " + info.width + " /Height " + info.height
                        + " /ColorSpace " + info.colorSpace() + info.decode()
                        + " /BitsPerComponent 8 /Filter /DCTDecode /Length " + page.jpeg.length()
                        + " >>\nstream\n");
                try (InputStream in = new FileInputStream(page.jpeg)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
                }
                os.ascii("\nendstream\nendobj\n");
            }

            long xref = os.count;
            os.ascii("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
            for (long off : offsets) os.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", off));
            os.ascii("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        }
    }

    private static int pageObj(int index) {
        return 3 + index * 3;
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ---------------------------------------------------------------------
    // JPEG-Kopf lesen: Breite, Höhe, Farbkanäle (aus dem SOF-Marker)
    // ---------------------------------------------------------------------

    static class JpegInfo {
        int width, height, components;

        String colorSpace() {
            return components == 1 ? "/DeviceGray" : components == 4 ? "/DeviceCMYK" : "/DeviceRGB";
        }

        /** CMYK-JPEGs (Adobe) sind invertiert gespeichert */
        String decode() {
            return components == 4 ? " /Decode [1 0 1 0 1 0 1 0]" : "";
        }
    }

    static JpegInfo readJpegInfo(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readUnsignedShort() != 0xFFD8) throw new IOException("Kein JPEG: " + file);
            while (true) {
                int marker = in.readUnsignedShort();
                while ((marker & 0xFF00) != 0xFF00) marker = in.readUnsignedShort();   // ungültige Bytes überspringen
                int type = marker & 0xFF;
                if (type == 0xD8 || type == 0x01 || (type >= 0xD0 && type <= 0xD7)) continue;   // ohne Länge
                int length = in.readUnsignedShort();
                // SOF0..SOF15 außer DHT (C4), JPG (C8), DAC (CC)
                if (type >= 0xC0 && type <= 0xCF && type != 0xC4 && type != 0xC8 && type != 0xCC) {
                    in.readUnsignedByte();   // Bit-Tiefe
                    JpegInfo info = new JpegInfo();
                    info.height = in.readUnsignedShort();
                    info.width = in.readUnsignedShort();
                    info.components = in.readUnsignedByte();
                    return info;
                }
                if (type == 0xDA || type == 0xD9) throw new IOException("Kein SOF-Marker: " + file);
                in.skipBytes(length - 2);
            }
        }
    }

    /** OutputStream, der mitzählt – für die Byte-Positionen in der xref-Tabelle */
    private static class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        void ascii(String s) throws IOException {
            write(s.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
