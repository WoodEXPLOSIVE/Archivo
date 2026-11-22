package at.woodexplosive.archivo.ui;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import at.woodexplosive.archivo.R;
import at.woodexplosive.archivo.data.LibraryRepository;
import at.woodexplosive.archivo.data.dao.ContentHit;
import at.woodexplosive.archivo.data.entity.Category;
import at.woodexplosive.archivo.data.entity.PdfFile;
import at.woodexplosive.archivo.ui.drawer.SearchResult;
import at.woodexplosive.archivo.ui.drawer.TreeItem;

/**
 * Gemeinsames ViewModel von MainActivity und allen Fragments ({@code requireActivity()} als Owner).
 * <p>
 * Baut aus Kategorien und PDFs den sichtbaren Baum der Seitenleiste ({@link #getTree()}), verwaltet
 * Auf-/Zuklappen, Mehrfachauswahl und Suche und reicht alle Änderungen an das
 * {@link LibraryRepository} weiter.
 */
public class LibraryViewModel extends AndroidViewModel {

    private final LibraryRepository repository;
    private final MediatorLiveData<List<TreeItem>> tree = new MediatorLiveData<>();
    private final Set<Long> expandedIds = new HashSet<>();
    private List<Category> lasCategories = new ArrayList<>();
    private List<PdfFile> lastPdfs = new ArrayList<>();
    private Long pendingImportCategoryId;

    // ---------- Mehrfachauswahl ----------
    private boolean selectionMode = false;
    private final Set<Long> selectedCategoryIds = new HashSet<>();
    private final Set<Long> selectedPdfIds = new HashSet<>();
    private final MutableLiveData<Boolean> selectionModeLive = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> selectedCount = new MutableLiveData<>(0);

    // ---------- Suche ----------
    private static final long SEARCH_DEBOUNCE_MS = 250;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::performSearch;
    private final MutableLiveData<List<SearchResult>> searchResults =
            new MutableLiveData<>(Collections.<SearchResult>emptyList());
    private String searchQuery = "";
    private boolean contentSearch = false;
    /** Zählt jede Suche hoch – so werden Antworten veralteter Suchen ignoriert */
    private int searchSeq = 0;

    // Kategorien sind asynchron geladen – Aktionen, die sie brauchen, warten hier
    private boolean categoriesLoaded = false;
    private final List<Runnable> afterCategoriesLoaded = new ArrayList<>();

    public LibraryViewModel(@NonNull Application application) {
        super(application);
        this.repository = new LibraryRepository(application);

        tree.addSource(repository.getCategories(), categories -> {
            lasCategories = categories;
            rebuildTree();
            refreshSearch();
            if (!categoriesLoaded) {
                categoriesLoaded = true;
                for (Runnable r : afterCategoriesLoaded) r.run();
                afterCategoriesLoaded.clear();
            }
        });
        tree.addSource(repository.getPdfFiles(), pdfs -> {
            lastPdfs = pdfs;
            rebuildTree();
            refreshSearch();   // z.B. Indizierung fertig → neue Inhaltstreffer
        });

        // Altbestand / abgebrochene Indizierung beim Start fortsetzen
        repository.indexPending();
    }

    // =====================================================================
    // Suche
    // =====================================================================

    /** Trefferliste der aktuellen Suche (leer, wenn nichts gesucht wird) */
    public LiveData<List<SearchResult>> getSearchResults() {
        return searchResults;
    }

    /** Anzahl der PDFs, deren Inhalt noch ausgelesen wird */
    public LiveData<Integer> getPendingIndexCount() {
        return repository.countPendingIndex();
    }

    public boolean isContentSearch() {
        return contentSearch;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    /** Mit kurzer Verzögerung suchen, damit nicht bei jedem getippten Buchstaben eine Abfrage läuft */
    public void setSearchQuery(String query) {
        String q = query == null ? "" : query.trim();
        if (q.equals(searchQuery)) return;
        searchQuery = q;
        searchHandler.removeCallbacks(searchRunnable);
        if (q.isEmpty()) {
            performSearch();   // leeren sofort
        } else {
            searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
        }
    }

    /** Inhaltssuche ein-/ausschalten und sofort neu suchen */
    public void setContentSearch(boolean enabled) {
        if (enabled == contentSearch) return;
        contentSearch = enabled;
        searchHandler.removeCallbacks(searchRunnable);
        performSearch();
    }

    private void refreshSearch() {
        if (!searchQuery.isEmpty()) performSearch();
    }

    private void performSearch() {
        int seq = ++searchSeq;
        String query = searchQuery;
        if (query.isEmpty()) {
            searchResults.setValue(Collections.<SearchResult>emptyList());
            return;
        }

        String needle = query.toLowerCase(Locale.ROOT);
        Map<Long, Long> parentOf = buildParentMap();
        Map<Long, String> categoryNames = new HashMap<>();
        for (Category c : lasCategories) categoryNames.put(c.id, c.name);

        // 1. Kategorien nach Name
        List<SearchResult> categoryResults = new ArrayList<>();
        for (Category c : lasCategories) {
            if (c.name.toLowerCase(Locale.ROOT).contains(needle)) {
                categoryResults.add(SearchResult.category(c.id, c.name,
                        categoryPath(c.parentId, parentOf, categoryNames)));
            }
        }

        // 2. PDFs nach Name (LinkedHashMap: Reihenfolge bleibt, Inhaltstreffer können Einträge ersetzen)
        Map<Long, SearchResult> pdfResults = new LinkedHashMap<>();
        for (PdfFile p : lastPdfs) {
            if (p.displayName.toLowerCase(Locale.ROOT).contains(needle)) {
                pdfResults.put(p.id, SearchResult.pdf(p,
                        categoryPath(p.categoryId, parentOf, categoryNames), -1));
            }
        }

        // Namenstreffer sofort anzeigen
        searchResults.setValue(merge(categoryResults, pdfResults));

        // 3. Optional: Volltext (asynchron, kommt danach dazu)
        String ftsQuery = buildFtsQuery(query);
        if (!contentSearch || ftsQuery.isEmpty()) return;

        repository.searchContent(ftsQuery, hits -> {
            if (seq != searchSeq) return;   // inzwischen wurde neu gesucht

            Map<Long, PdfFile> pdfById = new HashMap<>();
            for (PdfFile p : lastPdfs) pdfById.put(p.id, p);

            // Treffer pro PDF sammeln: alle Seiten + Ausschnitt der ersten Trefferseite
            // (die Abfrage liefert sortiert nach pdf_id, page)
            Map<Long, List<Integer>> pagesByPdf = new LinkedHashMap<>();
            Map<Long, String> firstSnippet = new HashMap<>();
            for (ContentHit hit : hits) {
                List<Integer> pages = pagesByPdf.get(hit.pdfId);
                if (pages == null) {
                    pages = new ArrayList<>();
                    pagesByPdf.put(hit.pdfId, pages);
                    firstSnippet.put(hit.pdfId, hit.snippet == null ? ""
                            : hit.snippet.replaceAll("\\s+", " ").trim());
                }
                pages.add(hit.page);
            }

            for (Map.Entry<Long, List<Integer>> e : pagesByPdf.entrySet()) {
                PdfFile p = pdfById.get(e.getKey());
                if (p == null) continue;
                List<Integer> pages = e.getValue();
                String subtitle = getApplication().getString(R.string.search_hit_page,
                        formatPages(pages), firstSnippet.get(p.id));
                // Antippen öffnet die erste Trefferseite
                pdfResults.put(p.id, SearchResult.pdf(p, subtitle, pages.get(0)));
            }
            searchResults.setValue(merge(categoryResults, pdfResults));
        });
    }

    private static final int MAX_LISTED_PAGES = 5;

    /** [0, 2, 4, 6, 8, 10, 12] → "1, 3, 5, 7, 9 +2" (Seiten 1-basiert für den User) */
    private static String formatPages(List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(pages.size(), MAX_LISTED_PAGES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pages.get(i) + 1);
        }
        if (pages.size() > shown) {
            sb.append(" +").append(pages.size() - shown);
        }
        return sb.toString();
    }

    private static List<SearchResult> merge(List<SearchResult> categories, Map<Long, SearchResult> pdfs) {
        List<SearchResult> all = new ArrayList<>(categories);
        all.addAll(pdfs.values());
        return all;
    }

    /**
     * Benutzereingabe → FTS-Syntax. Jedes Wort wird zum Präfix-Suchbegriff "wort*" (FTS4-Syntax; "wort"* wäre FTS5),
     * mehrere Wörter mit Leerzeichen = alle müssen vorkommen.
     * Anführungszeichen und * werden entfernt, damit keine ungültige Syntax entsteht.
     */
    static String buildFtsQuery(String input) {
        StringBuilder sb = new StringBuilder();
        for (String word : input.split("\\s+")) {
            String w = word.replace("\"", "").replace("*", "");
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('"').append(w).append("*\"");
        }
        return sb.toString();
    }

    /** "Mathe › Analysis" für eine Kategorie-ID (null = oberste Ebene → "") */
    private static String categoryPath(Long categoryId, Map<Long, Long> parentOf, Map<Long, String> names) {
        List<String> parts = new ArrayList<>();
        while (categoryId != null) {
            String name = names.get(categoryId);
            if (name == null) break;
            parts.add(0, name);
            categoryId = parentOf.get(categoryId);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" › ");
            sb.append(part);
        }
        return sb.toString();
    }

    /** Kategorie in der Seitenleiste sichtbar machen: alle Vorfahren + sie selbst aufklappen */
    public void revealCategory(long categoryId) {
        Map<Long, Long> parentOf = buildParentMap();
        Long id = categoryId;
        while (id != null) {
            expandedIds.add(id);
            id = parentOf.get(id);
        }
        rebuildTree();
    }

    /** Kategorie-ID → Eltern-ID */
    private Map<Long, Long> buildParentMap() {
        Map<Long, Long> parentOf = new HashMap<>();
        for (Category c : lasCategories) {
            parentOf.put(c.id, c.parentId);
        }
        return parentOf;
    }

    @Override
    protected void onCleared() {
        searchHandler.removeCallbacks(searchRunnable);
    }

    public LiveData<List<Category>> getCategories() {
        return repository.getCategories();
    }

    public LiveData<List<PdfFile>> getPdfFiles() {
        return repository.getPdfFiles();
    }

    /** Datei zu einem gespeicherten Dateinamen */
    public File getPdfFile(String fileName) {
        return repository.getFile(fileName);
    }

    // ---------- Lesestand ----------

    /** Ein PDF frisch aus der DB laden (null, wenn gelöscht) */
    public void getPdf(long id, LibraryRepository.ResultCallback<PdfFile> callback) {
        repository.getPdf(id, callback);
    }

    /** Seite ({@code page} 0-basiert) oder alle Seiten ({@code -1}) um 90° im Uhrzeigersinn drehen */
    public void rotatePages(long pdfId, int page,
                            LibraryRepository.ResultCallback<LibraryRepository.RotateResult> callback) {
        repository.getPdf(pdfId, pdf -> {
            if (pdf == null) {
                callback.onResult(LibraryRepository.RotateResult.FAILED);
                return;
            }
            repository.rotatePages(pdf, page, 90, callback);
        });
    }

    /**
     * Lesestand speichern, siehe {@link LibraryRepository#saveReadingPosition}
     */
    public void saveReadingPosition(long pdfId, int page, float pageOffset, float zoom, float scrollX) {
        repository.saveReadingPosition(pdfId, page, pageOffset, zoom, scrollX);
    }

    /** Seiten des PDFs, auf denen der Suchbegriff vorkommt (gleiche Logik wie die Inhaltssuche) */
    public void findHitPages(long pdfId, String query, LibraryRepository.ResultCallback<List<Integer>> callback) {
        String fts = buildFtsQuery(query);
        if (fts.isEmpty()) {
            callback.onResult(new ArrayList<Integer>());
            return;
        }
        repository.findHitPages(fts, pdfId, callback);
    }

    /** Merkt sich das geöffnete PDF für den nächsten App-Start */
    public void setLastOpenedPdf(long pdfId) {
        repository.setLastOpenedPdf(pdfId);
    }

    /** Vergisst das zuletzt geöffnete PDF – aber nur, wenn noch genau dieses eingetragen ist */
    public void clearLastOpenedPdf(long pdfId) {
        repository.clearLastOpenedPdf(pdfId);
    }

    /** Das zuletzt geöffnete PDF oder null */
    public void getLastOpenedPdf(LibraryRepository.ResultCallback<PdfFile> callback) {
        repository.getLastOpenedPdf(callback);
    }

    /** PDF verschieben; die Ziel-Kategorie wird aufgeklappt, damit man es gleich sieht */
    public void movePdf(long pdfId, long targetCategoryId) {
        expandedIds.add(targetCategoryId);
        repository.movePdf(pdfId, targetCategoryId);
    }

    /** Führt action aus, sobald die Kategorien aus der DB geladen sind (sofort, wenn schon geladen) */
    public void whenCategoriesLoaded(Runnable action) {
        if (categoriesLoaded) action.run();
        else afterCategoriesLoaded.add(action);
    }

    /** Kategorie anlegen und ihre neue ID zurückmelden (z.B. um gleich hinein zu importieren) */
    public void addCategory(String name, Long parentId, LibraryRepository.ResultCallback<Long> callback) {
        if (parentId != null) expandedIds.add(parentId);
        repository.addCategory(name, parentId, callback);
    }

    /** Gescanntes PDF in eine Kategorie übernehmen; Kategorie wird aufgeklappt */
    public void saveScan(long categoryId, String name, File pdf, LibraryRepository.ResultCallback<Boolean> callback) {
        expandedIds.add(categoryId);
        repository.saveScannedPdf(categoryId, name, pdf, callback);
    }

    /**
     * Kategorie anlegen; die Eltern-Kategorie wird aufgeklappt.
     * @param parentId Eltern-Kategorie, null = oberste Ebene
     */
    public void addCategory(String name, Long parentId) {
        if (parentId != null) {
            expandedIds.add(parentId);
        }
        repository.addCategory(name, parentId);
    }

    public void renameCategory(long id, String newName) {
        repository.renameCategory(id, newName);
    }

    /** Kategorie samt Unterkategorien, PDFs und Dateien löschen */
    public void deleteCategory(long id) {
        repository.deleteCategory(id);
    }

    public void renamePdf(long id, String newName) {
        repository.renamePdf(id, newName);
    }

    public void deletePdf(PdfFile pdf) {
        repository.deletePdf(pdf);
    }

    /**
     * Sichtbare Zeilen der Seitenleiste als flache Liste (nur aufgeklappte Kategorien zeigen ihren Inhalt)
     */
    public LiveData<List<TreeItem>> getTree() {
        return tree;
    }

    /** Kategorie auf- bzw. zuklappen */
    public void toggleExpanded(long categoryId) {
        if (!expandedIds.remove(categoryId)) expandedIds.add(categoryId);
        rebuildTree();
    }

    /** Gemerkte Ziel-Kategorie abholen und zurücksetzen (null = keine gemerkt) */
    public Long takePendingImportCategoryId() {
        Long id = pendingImportCategoryId;
        pendingImportCategoryId = null;
        return id;
    }

    /**
     * PDFs zum Teilen vorbereiten, siehe {@link LibraryRepository#prepareShare}
     */
    public void prepareShare(List<PdfFile> pdfs, LibraryRepository.ResultCallback<java.util.ArrayList<Uri>> callback) {
        repository.prepareShare(pdfs, callback);
    }

    /** Alle PDFs der Auswahl – ausgewählte Kategorien zählen mit allen PDFs ihrer Unterbäume */
    public List<PdfFile> getSelectedPdfsDeep() {
        Selection sel = getEffectiveSelection();
        Map<Long, List<Category>> children = groupByParent(lasCategories);
        Set<Long> categoryIds = new HashSet<>();
        java.util.ArrayDeque<Long> todo = new java.util.ArrayDeque<>(sel.categoryIds);
        while (!todo.isEmpty()) {
            Long id = todo.poll();
            if (!categoryIds.add(id)) continue;
            List<Category> kids = children.get(id);
            if (kids != null) for (Category c : kids) todo.add(c.id);
        }
        List<PdfFile> result = new ArrayList<>();
        for (PdfFile p : lastPdfs) {
            if (sel.pdfIds.contains(p.id) || categoryIds.contains(p.categoryId)) result.add(p);
        }
        return result;
    }

    /** Ziel-Kategorie merken, bevor die Dateiauswahl bzw. der Kamera-Scanner geöffnet wird */
    public void setPendingImportCategoryId(long categoryId) {
        pendingImportCategoryId = categoryId;
    }

    /**
     * Ausgewählte PDFs in die vorher gemerkte Kategorie importieren
     * (siehe {@link #setPendingImportCategoryId}). Ohne gemerkte Kategorie passiert nichts.
     */
    public void importPdfs(List<Uri> uris, LibraryRepository.ImportCallback callback) {
        Long categoryId = pendingImportCategoryId;
        pendingImportCategoryId = null;
        if (categoryId == null || uris == null || uris.isEmpty()) return;
        expandedIds.add(categoryId);
        repository.importPdfs(categoryId, uris, callback);
    }

    // =====================================================================
    // Mehrfachauswahl (Bulk Actions)
    // =====================================================================

    /** Bereinigte Auswahl: Elemente, deren Eltern-Kategorie selbst ausgewählt ist, fallen weg */
    public static class Selection {
        public final Set<Long> categoryIds;
        public final Set<Long> pdfIds;

        Selection(Set<Long> categoryIds, Set<Long> pdfIds) {
            this.categoryIds = categoryIds;
            this.pdfIds = pdfIds;
        }

        public int size() {
            return categoryIds.size() + pdfIds.size();
        }

        public boolean isEmpty() {
            return size() == 0;
        }
    }

    /** Ist die Mehrfachauswahl aktiv? (Seitenleiste zeigt dann Checkboxen und die Auswahl-Kopfzeile) */
    public LiveData<Boolean> getSelectionMode() {
        return selectionModeLive;
    }

    /** Anzahl der markierten Elemente */
    public LiveData<Integer> getSelectedCount() {
        return selectedCount;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** Auswahlmodus starten, mit dem ersten Element schon markiert */
    public void startSelection(TreeItem item) {
        selectionMode = true;
        selectionSetFor(item).add(item.id);
        updateSelection();
    }

    /** Markierung eines Elements umschalten */
    public void toggleSelection(TreeItem item) {
        Set<Long> set = selectionSetFor(item);
        if (!set.remove(item.id)) set.add(item.id);
        updateSelection();
    }

    /** Alle gerade in der Seitenleiste sichtbaren Elemente markieren */
    public void selectAllVisible() {
        List<TreeItem> visible = tree.getValue();
        if (visible == null) return;
        for (TreeItem item : visible) {
            selectionSetFor(item).add(item.id);
        }
        updateSelection();
    }

    /** Mehrfachauswahl beenden und alle Markierungen entfernen */
    public void clearSelection() {
        if (!selectionMode) return;
        selectionMode = false;
        selectedCategoryIds.clear();
        selectedPdfIds.clear();
        updateSelection();
    }

    /**
     * Was wirklich bearbeitet werden muss. Ist z.B. Mathe UND Mathe > Analysis markiert,
     * zaehlt nur Mathe - sonst wuerde Analysis beim Verschieben aus Mathe herausgeloest.
     */
    public Selection getEffectiveSelection() {
        Map<Long, Long> parentOf = buildParentMap();

        Set<Long> categories = new HashSet<>();
        for (Long id : selectedCategoryIds) {
            if (!isInSelectedCategory(parentOf.get(id), parentOf)) categories.add(id);
        }
        Set<Long> pdfs = new HashSet<>();
        for (PdfFile p : lastPdfs) {
            if (selectedPdfIds.contains(p.id) && !isInSelectedCategory(p.categoryId, parentOf)) {
                pdfs.add(p.id);
            }
        }
        return new Selection(categories, pdfs);
    }

    /** Liegt categoryId selbst oder einer seiner Vorfahren in der Auswahl? */
    private boolean isInSelectedCategory(Long categoryId, Map<Long, Long> parentOf) {
        while (categoryId != null) {
            if (selectedCategoryIds.contains(categoryId)) return true;
            categoryId = parentOf.get(categoryId);
        }
        return false;
    }

    /** @param target Ziel-Kategorie; null = oberste Ebene (nur wenn keine PDFs dabei sind) */
    public void moveSelection(Long target) {
        Selection sel = getEffectiveSelection();
        if (sel.isEmpty()) return;
        if (target == null && !sel.pdfIds.isEmpty()) return;   // PDFs brauchen immer eine Kategorie
        if (target != null) expandedIds.add(target);
        repository.moveMany(sel.categoryIds, sel.pdfIds, target);
        clearSelection();
    }

    /** Alle markierten Elemente löschen (Kategorien samt Inhalt) und die Auswahl beenden */
    public void deleteSelection() {
        Selection sel = getEffectiveSelection();
        if (sel.isEmpty()) return;
        List<PdfFile> pdfs = new ArrayList<>();
        for (PdfFile p : lastPdfs) {
            if (sel.pdfIds.contains(p.id)) pdfs.add(p);
        }
        repository.deleteMany(sel.categoryIds, pdfs);
        clearSelection();
    }

    private Set<Long> selectionSetFor(TreeItem item) {
        return item.type == TreeItem.TYPE_CATEGORY ? selectedCategoryIds : selectedPdfIds;
    }

    private void updateSelection() {
        selectionModeLive.setValue(selectionMode);
        rebuildTree();   // Checkboxen neu zeichnen + Anzahl aktualisieren
    }

    /** Markierungen fuer Elemente entfernen, die es nicht mehr gibt (z.B. geloescht) */
    private void pruneSelection() {
        Set<Long> existingCategories = new HashSet<>();
        for (Category c : lasCategories) existingCategories.add(c.id);
        Set<Long> existingPdfs = new HashSet<>();
        for (PdfFile p : lastPdfs) existingPdfs.add(p.id);

        selectedCategoryIds.retainAll(existingCategories);
        selectedPdfIds.retainAll(existingPdfs);
    }

    // =====================================================================

    private void rebuildTree() {
        pruneSelection();
        selectedCount.setValue(selectedCategoryIds.size() + selectedPdfIds.size());

        Map<Long, List<Category>> childrenByParent = groupByParent(lasCategories);
        Map<Long, List<PdfFile>> pdfByCategory = pdfByCategory(lastPdfs);

        List<TreeItem> result = new ArrayList<>();
        addChildren(null, 0, childrenByParent, pdfByCategory, result);
        tree.setValue(result);
    }

    /** Alle Kategorien als flache, eingerückte Liste – z.B. für "Verschieben nach" */
    public List<TreeItem> getAllCategoriesFlat() {
        return getAllCategoriesFlat(Collections.<Long>emptySet());
    }

    /**
     * Alle Kategorien als flache Liste.
     * @param excludeSubtreesOf diese Kategorien samt allen Unterkategorien weglassen
     */
    public List<TreeItem> getAllCategoriesFlat(Set<Long> excludeSubtreesOf) {
        List<TreeItem> result = new ArrayList<>();
        addCategoriesFlat(null, 0, groupByParent(lasCategories), excludeSubtreesOf, result);
        return result;
    }

    private void addCategoriesFlat(Long parentId, int depth,
                                   Map<Long, List<Category>> childrenByParent,
                                   Set<Long> excludeSubtreesOf,
                                   List<TreeItem> result) {
        List<Category> categories = childrenByParent.get(parentId);
        if (categories == null) return;
        for (Category c : categories) {
            // Ausgeschlossene Kategorie überspringen → ihre Kinder werden dadurch auch nie besucht
            if (excludeSubtreesOf.contains(c.id)) continue;
            result.add(TreeItem.category(c, depth, false, false));
            addCategoriesFlat(c.id, depth + 1, childrenByParent, excludeSubtreesOf, result);
        }
    }

    /** parentId einer Kategorie (null = oberste Ebene) */
    public Long getParentId(long categoryId) {
        for (Category c : lasCategories) {
            if (c.id == categoryId) return c.parentId;
        }
        return null;
    }

    /**
     * Kategorie verschieben; das Ziel wird aufgeklappt.
     * @param newParentId neue Eltern-Kategorie, null = oberste Ebene
     */
    public void moveCategory(long categoryId, Long newParentId) {
        if (newParentId != null) {
            expandedIds.add(newParentId);
        }
        repository.moveCategory(categoryId, newParentId);
    }

    /** PDFs nach ihrer Kategorie gruppieren (Schlüssel = Kategorie-ID) */
    @NonNull
    public Map<Long, List<PdfFile>> pdfByCategory(List<PdfFile> pdfFiles) {
        Map<Long, List<PdfFile>> pdfByCategory = new HashMap<>();
        for (PdfFile p : pdfFiles) {
            List<PdfFile> list = pdfByCategory.get(p.categoryId);
            if (list == null) {
                list = new ArrayList<>();
                pdfByCategory.put(p.categoryId, list);
            }
            list.add(p);
        }
        return pdfByCategory;
    }

    /** Kategorien nach ihrer Eltern-Kategorie gruppieren (Schlüssel null = oberste Ebene) */
    @NonNull
    public Map<Long, List<Category>> groupByParent(List<Category> categories) {
        Map<Long , List<Category>> childrenByParent = new HashMap<>();
        for (Category c : categories) {
            List<Category> list = childrenByParent.get(c.parentId);
            if (list == null) {
                list = new ArrayList<>();
                childrenByParent.put(c.parentId, list);
            }
            list.add(c);
        }
        return childrenByParent;
    }

    private void addChildren(Long parentId, int depth,
                             Map<Long, List<Category>> childrenByParent,
                             Map<Long, List<PdfFile>> pdfByCategory,
                             List<TreeItem> result) {
        List<Category> categories = childrenByParent.get(parentId);
        if (categories == null) return;

        for (Category c : categories) {
            boolean isExpanded = expandedIds.contains(c.id);
            boolean hasChildren = childrenByParent.containsKey(c.id) || pdfByCategory.containsKey(c.id);
            result.add(TreeItem.category(c, depth, isExpanded, hasChildren,
                    selectionMode, selectedCategoryIds.contains(c.id)));

            if (isExpanded) {
                addChildren(c.id, depth + 1, childrenByParent, pdfByCategory, result);
                List<PdfFile> pdfs = pdfByCategory.get(c.id);
                if (pdfs != null) {
                    for (PdfFile p : pdfs) {
                        result.add(TreeItem.pdf(p, depth + 1,
                                selectionMode, selectedPdfIds.contains(p.id)));
                    }
                }
            }
        }
    }
}
