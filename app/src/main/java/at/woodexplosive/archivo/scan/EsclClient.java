package at.woodexplosive.archivo.scan;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Spricht das eSCL-Protokoll ("AirScan" / "Mopria Scan"): einfache HTTP-Aufrufe mit XML.
 * <pre>
 *   GET  {base}/ScannerCapabilities   → was kann das Gerät (Glas, Einzug, Auflösungen, ...)
 *   GET  {base}/ScannerStatus         → z.B. ob Papier im Einzug liegt
 *   POST {base}/ScanJobs              → Scan starten, Antwort: Location = Job-URL
 *   GET  {job}/NextDocument           → nächste Seite als JPEG (404 = keine weiteren Seiten)
 * </pre>
 * Alle Methoden blockieren → nur im Hintergrund-Thread aufrufen.
 */
public class EsclClient {

    private static final String TAG = "EsclClient";

    public static final String SOURCE_PLATEN = "Platen";   // Scannerglas
    public static final String SOURCE_FEEDER = "Feeder";   // Einzug (ADF)
    public static final String COLOR_RGB = "RGB24";
    public static final String COLOR_GRAY = "Grayscale8";

    /** A4 in 1/300 Zoll (Einheit von eSCL) */
    private static final int A4_WIDTH = 2480;
    private static final int A4_HEIGHT = 3508;

    /** Wie lange wir bei "503 – noch beschäftigt" maximal warten (je 1 s) */
    private static final int MAX_BUSY_RETRIES = 90;

    /**
     * Was der Scanner kann: Glas/Einzug/Duplex, Auflösungen, Farbmodi, Formate und maximale Fläche
     * (in 1/300 Zoll). Aus {@code /ScannerCapabilities} gelesen.
     */
    public static class Capabilities {
        public String model = "";
        public String version = "2.0";
        public boolean platen;
        public boolean adf;
        public boolean duplex;
        public final TreeSet<Integer> resolutions = new TreeSet<>();
        public final Set<String> colorModes = new LinkedHashSet<>();
        public final Set<String> formats = new LinkedHashSet<>();
        public int maxWidth = A4_WIDTH;
        public int maxHeight = A4_HEIGHT;
    }

    /** Fehler mit verständlicher Ursache für die Anzeige */
    public static class ScanException extends IOException {
        public final Reason reason;

        public ScanException(Reason reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }
    }

    /** Fehlerursachen, für die es eine eigene Meldung gibt */
    public enum Reason { BUSY, ADF_EMPTY, NO_JPEG, HTTP }

    /** Meldet den Fortschritt eines Scan-Auftrags (auf dem Scan-Thread) */
    public interface PageListener {
        /** Seite empfangen (1-basiert, innerhalb dieses Scan-Auftrags) */
        void onPage(int pageNumber);
    }

    private final String baseUrl;

    public EsclClient(EsclScanner scanner) {
        this.baseUrl = scanner.baseUrl;
    }

    // =====================================================================
    // Fähigkeiten + Status
    // =====================================================================

    /**
     * Fähigkeiten des Scanners abfragen.
     * @throws IOException Scanner nicht erreichbar oder Antwort unbrauchbar
     */
    public Capabilities getCapabilities() throws IOException {
        HttpURLConnection c = open(baseUrl + "/ScannerCapabilities", "GET", 8000);
        try {
            expect(c, 200);
            try (InputStream in = c.getInputStream()) {
                return parseCapabilities(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** z.B. "ScannerAdfLoaded", "ScannerAdfEmpty" – oder null, wenn das Gerät keinen Einzug meldet */
    public String getAdfState() throws IOException {
        HttpURLConnection c = open(baseUrl + "/ScannerStatus", "GET", 8000);
        try {
            expect(c, 200);
            try (InputStream in = c.getInputStream()) {
                return findText(in, "AdfState");
            }
        } finally {
            c.disconnect();
        }
    }

    static Capabilities parseCapabilities(InputStream in) throws IOException {
        Capabilities caps = new Capabilities();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);   // getName() ohne "scan:"-Präfix
            p.setInput(in, null);
            boolean versionSet = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != XmlPullParser.START_TAG) continue;
                switch (p.getName()) {
                    case "MakeAndModel":
                        caps.model = p.nextText().trim();
                        break;
                    case "Version":
                        String v = p.nextText().trim();
                        if (!versionSet && !v.isEmpty()) {
                            caps.version = v;
                            versionSet = true;
                        }
                        break;
                    case "Platen":
                        caps.platen = true;
                        break;
                    case "Adf":
                        caps.adf = true;
                        break;
                    case "AdfDuplexInputCaps":
                        caps.duplex = true;
                        break;
                    case "AdfOption":
                        // Manche Geräte melden Duplex nur so: <scan:AdfOptions><scan:AdfOption>Duplex</...>
                        if ("Duplex".equalsIgnoreCase(p.nextText().trim())) caps.duplex = true;
                        break;
                    case "XResolution":
                        Integer res = parseInt(p.nextText());
                        if (res != null) caps.resolutions.add(res);
                        break;
                    case "ColorMode":
                        caps.colorModes.add(p.nextText().trim());
                        break;
                    case "DocumentFormat":
                    case "DocumentFormatExt":
                        caps.formats.add(p.nextText().trim());
                        break;
                    case "MaxWidth":
                        Integer w = parseInt(p.nextText());
                        if (w != null) caps.maxWidth = w;
                        break;
                    case "MaxHeight":
                        Integer h = parseInt(p.nextText());
                        if (h != null) caps.maxHeight = h;
                        break;
                    default:
                        break;
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Ungültige ScannerCapabilities", e);
        }
        return caps;
    }

    // =====================================================================
    // Scannen
    // =====================================================================

    /**
     * Scannt eine Seite (Glas) bzw. alle Seiten (Einzug) als JPEG-Dateien in {@code dir}.
     * @param duplex nur beim Einzug: beide Seiten jedes Blatts. Der Scanner liefert sie in
     *               Lesereihenfolge (Blatt 1 vorne, Blatt 1 hinten, Blatt 2 vorne, ...).
     * @throws InterruptedException wenn abgebrochen wurde (Thread unterbrochen)
     */
    public List<File> scan(String source, String colorMode, int dpi, boolean duplex, Capabilities caps,
                           File dir, PageListener listener) throws IOException, InterruptedException {
        if (!caps.formats.isEmpty() && !caps.formats.contains("image/jpeg")) {
            throw new ScanException(Reason.NO_JPEG, caps.formats.toString());
        }
        if (SOURCE_FEEDER.equals(source)) {
            String adf = getAdfState();
            if ("ScannerAdfEmpty".equals(adf)) throw new ScanException(Reason.ADF_EMPTY, adf);
        }

        boolean useDuplex = duplex && caps.duplex && SOURCE_FEEDER.equals(source);
        String jobUrl = createJob(source, colorMode, dpi, useDuplex, caps);
        Log.i(TAG, "Scan-Job: " + jobUrl);

        List<File> pages = new ArrayList<>();
        try {
            while (true) {
                File page = new File(dir, "page-" + System.nanoTime() + ".jpg");
                if (!nextDocument(jobUrl, page)) break;   // 404 → fertig
                pages.add(page);
                if (listener != null) listener.onPage(pages.size());
                // Glas liefert genau eine Seite; manche Geräte antworten danach nicht sauber mit 404
                if (SOURCE_PLATEN.equals(source)) break;
            }
        } finally {
            deleteQuietly(jobUrl);
        }
        return pages;
    }

    private String createJob(String source, String colorMode, int dpi, boolean duplex,
                             Capabilities caps) throws IOException {
        int width = Math.min(A4_WIDTH, caps.maxWidth);
        int height = Math.min(A4_HEIGHT, caps.maxHeight);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<scan:ScanSettings xmlns:scan=\"http://schemas.hp.com/imaging/escl/2011/05/03\""
                + " xmlns:pwg=\"http://www.pwg.org/schemas/2010/12/sm\">\n"
                + "  <pwg:Version>" + caps.version + "</pwg:Version>\n"
                + "  <scan:Intent>Document</scan:Intent>\n"
                + "  <pwg:ScanRegions>\n"
                + "    <pwg:ScanRegion>\n"
                + "      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>\n"
                + "      <pwg:XOffset>0</pwg:XOffset>\n"
                + "      <pwg:YOffset>0</pwg:YOffset>\n"
                + "      <pwg:Width>" + width + "</pwg:Width>\n"
                + "      <pwg:Height>" + height + "</pwg:Height>\n"
                + "    </pwg:ScanRegion>\n"
                + "  </pwg:ScanRegions>\n"
                + "  <pwg:InputSource>" + source + "</pwg:InputSource>\n"
                // Duplex nur mitschicken, wenn gewünscht – manche Geräte stolpern über unbekannte Felder
                + (duplex ? "  <scan:Duplex>true</scan:Duplex>\n" : "")
                + "  <scan:ColorMode>" + colorMode + "</scan:ColorMode>\n"
                + "  <scan:XResolution>" + dpi + "</scan:XResolution>\n"
                + "  <scan:YResolution>" + dpi + "</scan:YResolution>\n"
                + "  <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>\n"
                + "  <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>\n"
                + "</scan:ScanSettings>\n";

        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(baseUrl + "/ScanJobs", "POST", 20000);
            try {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml");
                byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
                int code = c.getResponseCode();
                if (code == 201) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new ScanException(Reason.HTTP, "Keine Job-URL");
                    String job = new URL(new URL(baseUrl + "/"), location).toString();
                    return job.endsWith("/") ? job.substring(0, job.length() - 1) : job;
                }
                if ((code == 503 || code == 409) && attempt < 5) {
                    sleep(2000);   // Scanner wacht gerade auf / ist kurz beschäftigt
                    continue;
                }
                if (code == 503 || code == 409) throw new ScanException(Reason.BUSY, "HTTP " + code);
                throw new ScanException(Reason.HTTP, "ScanJobs HTTP " + code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Abgebrochen", e);
            } finally {
                c.disconnect();
            }
        }
    }

    /** @return true = Seite gespeichert, false = keine weiteren Seiten (404) */
    private boolean nextDocument(String jobUrl, File target) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < MAX_BUSY_RETRIES; attempt++) {
            if (Thread.interrupted()) throw new InterruptedException();
            // Lange Lesezeit: Das Gerät scannt, während wir auf die Antwort warten
            HttpURLConnection c = open(jobUrl + "/NextDocument", "GET", 180000);
            try {
                int code = c.getResponseCode();
                if (code == 200) {
                    try (InputStream in = c.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    return true;
                }
                if (code == 404 || code == 410) return false;
                if (code == 503) {   // Seite noch nicht fertig
                    sleep(1000);
                    continue;
                }
                throw new ScanException(Reason.HTTP, "NextDocument HTTP " + code);
            } finally {
                c.disconnect();
            }
        }
        throw new ScanException(Reason.BUSY, "Zeitüberschreitung");
    }

    // =====================================================================
    // Hilfsmethoden
    // =====================================================================

    private static HttpURLConnection open(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        return c;
    }

    private static void expect(HttpURLConnection c, int code) throws IOException {
        int actual = c.getResponseCode();
        if (actual != code) throw new ScanException(Reason.HTTP, c.getURL() + " → HTTP " + actual);
    }

    private static void deleteQuietly(String jobUrl) {
        try {
            HttpURLConnection c = open(jobUrl, "DELETE", 5000);
            c.getResponseCode();
            c.disconnect();
        } catch (IOException ignored) {
            // Aufräumen ist optional – viele Geräte löschen fertige Jobs selbst
        }
    }

    private static String findText(InputStream in, String localName) throws IOException {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(in, null);
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && localName.equals(p.getName())) {
                    return p.nextText().trim();
                }
            }
            return null;
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Für Logs/Fehlertexte */
    public static String describe(Capabilities caps) {
        return String.format(Locale.ROOT, "%s (eSCL %s) Glas=%b Einzug=%b Duplex=%b dpi=%s Farben=%s Formate=%s",
                caps.model, caps.version, caps.platen, caps.adf, caps.duplex,
                caps.resolutions, caps.colorModes, caps.formats);
    }
}
