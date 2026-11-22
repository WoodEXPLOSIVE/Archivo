package at.woodexplosive.learnarchiver.ui.scan;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import at.woodexplosive.learnarchiver.R;
import at.woodexplosive.learnarchiver.scan.BlankPageDetector;
import at.woodexplosive.learnarchiver.scan.EsclClient;
import at.woodexplosive.learnarchiver.scan.EsclScanner;
import at.woodexplosive.learnarchiver.scan.JpegPdfWriter;
import at.woodexplosive.learnarchiver.scan.ScannerDiscovery;

/**
 * Zustand des Scan-Bildschirms. Überlebt Drehungen, damit ein laufender Scan nicht abbricht.
 * Gescannte Seiten werden als JPEGs im Cache gesammelt – erst "Fertig" baut daraus das PDF.
 */
public class ScanViewModel extends AndroidViewModel {

    private static final String TAG = "ScanViewModel";

    /** Was der Bildschirm gerade anzeigen soll */
    public static class State {
        public final boolean busy;
        @Nullable public final String message;
        public final boolean error;
        public final int pageCount;
        /** Gescannte Seiten (JPEG + Drehung) in PDF-Reihenfolge – für die Vorschau */
        @NonNull public final List<JpegPdfWriter.Page> pages;
        /** Fertiges PDF (einmalig abholen, dann consumeResult()) */
        @Nullable public final File resultPdf;

        State(boolean busy, @Nullable String message, boolean error, @NonNull List<JpegPdfWriter.Page> pages,
              @Nullable File resultPdf) {
            this.busy = busy;
            this.message = message;
            this.error = error;
            this.pages = pages;
            this.pageCount = pages.size();
            this.resultPdf = resultPdf;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScannerDiscovery discovery;
    private final File pageDir;

    private final List<EsclScanner> foundScanners = new ArrayList<>();
    private final MutableLiveData<List<EsclScanner>> scanners = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<EsclScanner> selected = new MutableLiveData<>();
    private final MutableLiveData<EsclClient.Capabilities> capabilities = new MutableLiveData<>();
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, null, false, new ArrayList<JpegPdfWriter.Page>(), null));

    private final List<JpegPdfWriter.Page> pages = new ArrayList<>();
    @Nullable private Future<?> currentTask;

    public ScanViewModel(@NonNull Application app) {
        super(app);
        // Reste von abgebrochenen Sitzungen (App beendet/abgestürzt) wegräumen
        File root = new File(app.getFilesDir(), "scan-tmp");
        deleteRecursive(root);
        // Unter files/ statt cache/: Dateien behalten beim Verschieben ihre Gruppe – aus dem
        // Cache-Ordner würde das fertige PDF in den Einstellungen als "Cache" gezählt
        pageDir = new File(root, UUID.randomUUID().toString());
        pageDir.mkdirs();
        discovery = new ScannerDiscovery(app, this::addScanner);
    }

    /** Alle bisher im WLAN gefundenen Scanner */
    public LiveData<List<EsclScanner>> getScanners() { return scanners; }
    /** Der ausgewählte Scanner */
    public LiveData<EsclScanner> getSelected() { return selected; }
    /** Fähigkeiten des ausgewählten Scanners (null, solange sie geladen werden) */
    public LiveData<EsclClient.Capabilities> getCapabilities() { return capabilities; }
    /** Aktueller Zustand für die Anzeige: Statusmeldung, gescannte Seiten, fertiges PDF */
    public LiveData<State> getState() { return state; }

    /** Scanner im WLAN suchen; der erste gefundene wird automatisch ausgewählt */
    public void startDiscovery() {
        discovery.start();
    }

    /** Wird vom Discovery-Thread aufgerufen */
    private synchronized void addScanner(EsclScanner scanner) {
        if (foundScanners.contains(scanner)) return;
        foundScanners.add(scanner);
        scanners.postValue(new ArrayList<>(foundScanners));
        // Den ersten gefundenen Scanner gleich auswählen
        if (foundScanners.size() == 1 && selected.getValue() == null) {
            selectAsync(scanner);
        }
    }

    /** Anderen Scanner auswählen und seine Fähigkeiten laden */
    public void select(EsclScanner scanner) {
        if (scanner.equals(selected.getValue())) return;
        selectAsync(scanner);
    }

    /** Scanner auswählen und seine Fähigkeiten laden (Glas/Einzug, Auflösungen, ...) */
    private void selectAsync(EsclScanner scanner) {
        selected.postValue(scanner);
        capabilities.postValue(null);
        postState(true, str(R.string.scan_status_connecting, scanner.name), false);
        executor.execute(() -> {
            try {
                EsclClient.Capabilities caps = new EsclClient(scanner).getCapabilities();
                Log.i(TAG, EsclClient.describe(caps));
                capabilities.postValue(caps);
                postState(false, pages.isEmpty() ? str(R.string.scan_status_ready) : pagesMessage(), false);
            } catch (IOException e) {
                Log.w(TAG, "Fähigkeiten nicht lesbar: " + scanner.baseUrl, e);
                postState(false, str(R.string.scan_error_connect, scanner.name), true);
            }
        });
    }

    /**
     * Scanner per IP hinzufügen (falls die automatische Suche nichts findet).
     * Probiert die üblichen eSCL-Adressen durch.
     */
    public void addManual(String ip) {
        // Tastaturen machen aus "192.168." gern "192. 168." (Leerzeichen nach dem Punkt wie
        // nach einem Satz) – also alle Leerzeichen raus, ebenso ein eingegebenes "http://" oder "/"
        String host = ip.replaceAll("\\s+", "")
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
        if (host.isEmpty()) return;
        postState(true, str(R.string.scan_status_connecting, host), false);
        executor.execute(() -> {
            String[] candidates = {
                    "http://" + host + ":8080/eSCL",   // HP und viele andere
                    "http://" + host + "/eSCL",
                    "http://" + host + ":80/eSCL",
            };
            for (String base : candidates) {
                EsclScanner probe = new EsclScanner(host, base);
                try {
                    EsclClient.Capabilities caps = new EsclClient(probe).getCapabilities();
                    EsclScanner scanner = new EsclScanner(caps.model.isEmpty() ? host : caps.model, base);
                    synchronized (this) {
                        if (!foundScanners.contains(scanner)) foundScanners.add(scanner);
                        scanners.postValue(new ArrayList<>(foundScanners));
                    }
                    selected.postValue(scanner);
                    capabilities.postValue(caps);
                    postState(false, pages.isEmpty() ? str(R.string.scan_status_ready) : pagesMessage(), false);
                    return;
                } catch (IOException e) {
                    Log.i(TAG, "Kein eSCL unter " + base + ": " + e.getMessage());
                }
            }
            postState(false, str(R.string.scan_error_connect, host), true);
        });
    }

    /** Scannt mit den gewählten Einstellungen; Seiten werden an die bisherigen angehängt */
    /**
     * @param removeBlankBacks nur bei Duplex: leere Rückseiten (jede 2. Seite des Auftrags) gleich verwerfen
     */
    public void scan(String source, String colorMode, int dpi, boolean duplex, boolean removeBlankBacks) {
        EsclScanner scanner = selected.getValue();
        EsclClient.Capabilities caps = capabilities.getValue();
        if (scanner == null || caps == null || isBusy()) return;

        postState(true, str(R.string.scan_status_scanning), false);
        currentTask = executor.submit(() -> {
            try {
                int before = pageCount();
                List<File> newPages = new EsclClient(scanner).scan(source, colorMode, dpi, duplex, caps, pageDir,
                        n -> postState(true, str(R.string.scan_status_page_received, before + n), false));
                // Duplex liefert vorne, hinten, vorne, hinten, … → Rückseiten = ungerade Indizes
                int removed = 0;
                List<File> kept = new ArrayList<>();
                for (int i = 0; i < newPages.size(); i++) {
                    File f = newPages.get(i);
                    if (duplex && removeBlankBacks && i % 2 == 1 && BlankPageDetector.isBlank(f)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        removed++;
                    } else {
                        kept.add(f);
                    }
                }
                synchronized (this) {
                    for (File f : kept) pages.add(new JpegPdfWriter.Page(f, dpi));
                }
                String msg = pagesMessage();
                if (removed > 0) {
                    msg += " " + getApplication().getResources()
                            .getQuantityString(R.plurals.scan_status_blank_removed, removed, removed);
                }
                postState(false, msg, false);
            } catch (InterruptedException e) {
                postState(false, str(R.string.scan_status_cancelled), false);
            } catch (EsclClient.ScanException e) {
                Log.w(TAG, "Scan fehlgeschlagen", e);
                postState(false, errorText(e.reason), true);
            } catch (IOException e) {
                Log.w(TAG, "Scan fehlgeschlagen", e);
                postState(false, Thread.currentThread().isInterrupted()
                        ? str(R.string.scan_status_cancelled) : str(R.string.scan_error_generic), true);
            }
        });
    }

    /** Laufenden Scan abbrechen (schon empfangene Seiten bleiben erhalten) */
    public void cancelScan() {
        if (currentTask != null) currentTask.cancel(true);
    }

    /** Alle bisher gescannten Seiten verwerfen */
    public synchronized void discardPages() {
        for (JpegPdfWriter.Page p : pages) deletePageFile(p);
        pages.clear();
        postState(false, str(R.string.scan_status_ready), false);
    }

    /** Eine gescannte Seite aus der Vorschau entfernen */
    public synchronized void removePage(int index) {
        if (isBusy() || index < 0 || index >= pages.size()) return;
        deletePageFile(pages.remove(index));
        postState(false, pages.isEmpty() ? str(R.string.scan_status_ready) : pagesMessage(), false);
    }

    /** Seite um 90° im Uhrzeigersinn drehen (verlustfrei, erst beim Speichern als /Rotate ins PDF) */
    public synchronized void rotatePage(int index) {
        if (isBusy() || index < 0 || index >= pages.size()) return;
        pages.set(index, pages.get(index).rotatedClockwise());
        postState(false, pagesMessage(), false);
    }

    /** Seite in der Vorschau verschieben (Ziehen) */
    public synchronized void movePage(int from, int to) {
        if (isBusy() || from == to || from < 0 || to < 0 || from >= pages.size() || to >= pages.size()) return;
        pages.add(to, pages.remove(from));
        postState(false, pagesMessage(), false);
    }

    /** Seiten zu einem PDF zusammenfügen → State.resultPdf */
    public void finish() {
        if (pageCount() == 0 || isBusy()) return;
        postState(true, str(R.string.scan_status_saving), false);
        executor.execute(() -> {
            File pdf = new File(pageDir, "scan.pdf");
            try {
                List<JpegPdfWriter.Page> copy;
                synchronized (this) {
                    copy = new ArrayList<>(pages);
                }
                JpegPdfWriter.write(copy, pdf);
                state.postValue(new State(false, null, false, copy, pdf));
            } catch (IOException e) {
                Log.w(TAG, "PDF erstellen fehlgeschlagen", e);
                pdf.delete();
                postState(false, str(R.string.scan_error_generic), true);
            }
        });
    }

    /** Ergebnis wurde übernommen (in die Bibliothek verschoben) → Seiten aufräumen */
    public synchronized void consumeResult() {
        for (JpegPdfWriter.Page p : pages) deletePageFile(p);
        pages.clear();
        state.setValue(new State(false, null, false, new ArrayList<JpegPdfWriter.Page>(), null));
    }

    // ---------------------------------------------------------------------

    private boolean isBusy() {
        State s = state.getValue();
        return s != null && s.busy;
    }

    private synchronized int pageCount() {
        return pages.size();
    }

    private String pagesMessage() {
        int n = pageCount();
        return getApplication().getResources().getQuantityString(R.plurals.scan_status_pages, n, n);
    }

    private void postState(boolean busy, String message, boolean error) {
        List<JpegPdfWriter.Page> copy;
        synchronized (this) {
            copy = new ArrayList<>(pages);
        }
        state.postValue(new State(busy, message, error, copy, null));
    }

    private String str(int res, Object... args) {
        return getApplication().getString(res, args);
    }

    private String errorText(EsclClient.Reason reason) {
        switch (reason) {
            case ADF_EMPTY: return str(R.string.scan_error_adf_empty);
            case BUSY: return str(R.string.scan_error_busy);
            case NO_JPEG: return str(R.string.scan_error_no_jpeg);
            default: return str(R.string.scan_error_generic);
        }
    }

    private static void deletePageFile(JpegPdfWriter.Page p) {
        //noinspection ResultOfMethodCallIgnored
        p.jpegFile().delete();
    }

    @Override
    protected void onCleared() {
        discovery.stop();
        executor.shutdownNow();
        // Temporäre Seiten wegräumen (ein evtl. fertiges PDF wurde schon verschoben)
        deleteRecursive(pageDir);
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
