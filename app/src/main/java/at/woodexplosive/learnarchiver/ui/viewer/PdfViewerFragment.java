package at.woodexplosive.learnarchiver.ui.viewer;

import android.graphics.RectF;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import at.woodexplosive.learnarchiver.R;
import at.woodexplosive.learnarchiver.data.enitity.PdfFile;
import at.woodexplosive.learnarchiver.databinding.FragmentPdfViewerBinding;
import at.woodexplosive.learnarchiver.ui.LibraryViewModel;

public class PdfViewerFragment extends Fragment {
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 3f;
    private static final float DOUBLE_TAP_ZOOM = 2f;
    private static final String STATE_HITS_CLOSED = "hitsClosed";
    public static final String ARG_PDF_ID = "pdfId";
    /** Optional: auf dieser Seite öffnen (z.B. Suchtreffer); -1 = gespeicherter Lesestand */
    public static final String ARG_START_PAGE = "startPage";
    /** Optional: Suchbegriff, der auf den Seiten markiert wird */
    public static final String ARG_HIGHLIGHT = "highlight";
    public static final String ARG_FILE_NAME = "fileName";
    public static final String ARG_TITLE = "title";

    private FragmentPdfViewerBinding binding;
    private PdfDocumentRenderer renderer;
    private float zoom = 1f;
    private PdfPageAdapter adapter;
    private LibraryViewModel viewModel;
    private long pdfId = -1;
    private int startPage = -1;
    /** Toolbar-Untertitel dieses Viewers (z.B. "Treffer für ...") oder null */
    @Nullable private String subtitle;
    /** Seiten mit Suchtreffern (aufsteigend, 0-basiert) – leer, wenn nicht aus der Inhaltssuche geöffnet */
    private List<Integer> hitPages = new ArrayList<>();
    /** Treffer-Leiste wurde mit ✕ geschlossen */
    private boolean hitsClosed;
    /**
     * Treffer, zu dem zuletzt gesprungen wurde (-1 = keiner bzw. User hat seither selbst gescrollt).
     * Nötig, weil sich die letzte Seite nicht bis ganz nach oben scrollen lässt – aus der ersten
     * sichtbaren Seite allein wäre der Sprung dorthin nicht erkennbar.
     */
    private int hitIndex = -1;
    private File file;
    @Nullable private String highlight;
    /** Eine Drehung läuft gerade (weitere Klicks ignorieren) */
    private boolean rotating;
    /**
     * Zuletzt gedrehte Seite (-1 = keine bzw. User hat seither selbst gescrollt). Mehrmals Drehen
     * trifft so immer dieselbe Seite – auch die letzte, die sich nicht bis in die Mitte scrollen lässt.
     */
    private int rotatedPage = -1;
    /** Seite, auf der nach einem Sprung noch zum Treffer-Wort gescrollt werden soll (-1 = keine) */
    private int revealPage = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPdfViewerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Zoom, Seite und Position kommen beim Öffnen aus der DB (auch nach einer Drehung,
        // denn onPause speichert vorher den Lesestand)
        if (savedInstanceState != null) {
            hitsClosed = savedInstanceState.getBoolean(STATE_HITS_CLOSED, false);
        }
        Bundle args = requireArguments();
        pdfId = args.getLong(ARG_PDF_ID, -1);
        // Startseite nur beim ersten Öffnen – nach einer Drehung gilt der gerade gespeicherte Lesestand
        startPage = savedInstanceState == null ? args.getInt(ARG_START_PAGE, -1) : -1;
        String fileName = args.getString(ARG_FILE_NAME);
        requireActivity().setTitle(args.getString(ARG_TITLE));

        // Teilen-Symbol in der Toolbar – nur solange dieser Viewer sichtbar ist
        requireActivity().addMenuProvider(new androidx.core.view.MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater inflater) {
                inflater.inflate(R.menu.menu_viewer, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull android.view.MenuItem item) {
                if (item.getItemId() == R.id.action_rotate_page) {
                    rotate(false);
                    return true;
                }
                if (item.getItemId() == R.id.action_rotate_all) {
                    rotate(true);
                    return true;
                }
                if (item.getItemId() != R.id.action_share) return false;
                viewModel.getPdf(pdfId, pdf -> {
                    if (pdf != null && isAdded()) {
                        at.woodexplosive.learnarchiver.ui.ShareHelper.share(requireActivity(), viewModel,
                                java.util.Collections.singletonList(pdf));
                    }
                });
                return true;
            }
        }, getViewLifecycleOwner(), androidx.lifecycle.Lifecycle.State.RESUMED);

        viewModel = new ViewModelProvider(requireActivity()).get(LibraryViewModel.class);
        file = viewModel.getPdfFile(fileName);
        viewModel.setLastOpenedPdf(pdfId);
        observePdfStillExists();

        highlight = args.getString(ARG_HIGHLIGHT);
        subtitle = highlight == null || highlight.isEmpty()
                ? null : getString(R.string.viewer_highlight_subtitle, highlight);
        binding.pages.setLayoutManager(new LinearLayoutManager(requireContext()));
        openDocument(-1);
        binding.pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) rotatedPage = -1;   // User scrollt selbst
            }
        });

        setupHitBar(highlight);

        binding.zoomContainer.setZoomListener(new ZoomContainer.ZoomListener() {
            @Override
            public void onZoom(float gestureScale, float focusX, float focusY) {
                float preview = clampZoom(zoom * gestureScale) / zoom;
                binding.pages.setPivotX(binding.hScroll.getScrollX() + focusX);
                binding.pages.setPivotY(focusY);
                binding.pages.setScaleX(preview);
                binding.pages.setScaleY(preview);
            }

            @Override
            public void onZoomEnd(float gestureScale, float focusX, float focusY) {
                applyZoom(clampZoom(zoom * gestureScale), focusX, focusY);
            }

            @Override
            public void onDoubleTap(float x, float y) {
                // Umschalten: eingezoomt -> 1x, sonst -> 2x um den getippten Punkt
                applyZoom(zoom > MIN_ZOOM ? MIN_ZOOM : DOUBLE_TAP_ZOOM, x, y);
            }

            // ----- Schräges Schieben (nur eingezoomt, sonst scrollt die RecyclerView wie gewohnt) -----

            /** Bruchteile eines Pixels sammeln, sonst hinkt der Inhalt bei langsamen Bewegungen hinterher */
            private float restX, restY;

            @Override
            public boolean isPanEnabled() {
                return binding != null && (binding.hScroll.canScrollHorizontally(1)
                        || binding.hScroll.canScrollHorizontally(-1));
            }

            @Override
            public void onPanStart() {
                // Wie ein normales Scrollen durch den User: gemerkte Treffer-/Dreh-Seite vergessen
                hitIndex = -1;
                rotatedPage = -1;
                revealPage = -1;
                restX = restY = 0f;
                binding.pages.stopScroll();
            }

            @Override
            public void onPan(float dx, float dy) {
                // Inhalt folgt dem Finger: Finger nach rechts → nach links scrollen
                restX -= dx;
                restY -= dy;
                int sx = (int) restX;
                int sy = (int) restY;
                restX -= sx;
                restY -= sy;
                if (sx != 0) binding.hScroll.scrollBy(sx, 0);
                if (sy != 0) binding.pages.scrollBy(0, sy);
            }

            @Override
            public void onPanFling(float velocityX, float velocityY) {
                binding.hScroll.fling(Math.round(-velocityX));
                binding.pages.fling(0, Math.round(-velocityY));
            }
        });
    }

    /**
     * Renderer + Adapter für die Datei anlegen und öffnen.
     * @param keepPage -1 = gespeicherten Lesestand wiederherstellen (normales Öffnen),
     *                 sonst nach dem Neuladen (Drehen) oben auf dieser Seite weitermachen
     */
    private void openDocument(int keepPage) {
        renderer = new PdfDocumentRenderer();
        renderer.setHighlight(highlight);
        adapter = new PdfPageAdapter(renderer, getResources().getDisplayMetrics().widthPixels);
        binding.pages.setAdapter(adapter);

        renderer.open(file, new PdfDocumentRenderer.OpenCallback() {
            @Override
            public void onOpened(List<Float> aspectRatios) {
                if (binding == null) return;
                // post: erst nach dem Layout ist die Breite der HorizontalScrollView bekannt
                binding.pages.post(() -> {
                    if (binding == null) return;
                    if (keepPage < 0) {
                        restoreReadingPosition(aspectRatios);
                    } else {
                        adapter.setPages(aspectRatios, updateRecyclerWidth());
                        LinearLayoutManager lm = (LinearLayoutManager) binding.pages.getLayoutManager();
                        int page = Math.min(keepPage, Math.max(0, aspectRatios.size() - 1));
                        if (lm != null) lm.scrollToPositionWithOffset(page, 0);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                if (binding == null) return;
                Toast.makeText(requireContext(), R.string.pdf_open_failed, Toast.LENGTH_LONG).show();
                NavHostFragment.findNavController(PdfViewerFragment.this).popBackStack();
            }
        });
    }

    /**
     * Die Seite, die man gerade ansieht. Maßgeblich ist eine "Leselinie", die mit dem Scrollfortschritt
     * wandert: ganz oben im Dokument am oberen Rand, ganz unten am unteren Rand, dazwischen ungefähr in der
     * Mitte. So ist jede Seite erreichbar – auch eine niedrige (quer gedrehte) letzte Seite, die nie bis
     * zur Bildschirmmitte scrollen kann. Mit einer festen Mittellinie würde dort die vorletzte gedreht.
     */
    private int pageAtCenter() {
        RecyclerView rv = binding.pages;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        if (lm == null) return 0;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return 0;

        float progress;   // 0 = ganz oben, 1 = ganz unten
        if (!rv.canScrollVertically(1)) {
            progress = rv.canScrollVertically(-1) ? 1f : 0.5f;   // passt alles auf den Schirm → Mitte
        } else if (!rv.canScrollVertically(-1)) {
            progress = 0f;
        } else {
            int max = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent();
            progress = max > 0 ? Math.min(1f, Math.max(0f, rv.computeVerticalScrollOffset() / (float) max)) : 0.5f;
        }
        int top = rv.getPaddingTop();
        int bottom = rv.getHeight() - rv.getPaddingBottom();
        int line = Math.min(bottom - 1, Math.round(top + progress * (bottom - top)));

        int best = first;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            View v = lm.findViewByPosition(i);
            if (v == null) continue;
            int t = lm.getDecoratedTop(v);
            int b = lm.getDecoratedBottom(v);
            if (t <= line && b > line) return i;
            // Linie liegt im Abstand zwischen zwei Seiten → die nächstgelegene nehmen
            int d = line < t ? t - line : line - b;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Aktuelle Seite (oder alle) um 90° drehen. Die Datei wird dabei neu geschrieben → danach
     * Renderer schließen und das PDF neu öffnen (PdfRenderer hält noch die alte Datei offen).
     */
    private void rotate(boolean allPages) {
        if (rotating || adapter == null || adapter.getItemCount() == 0) return;
        rotating = true;
        int current = !allPages && rotatedPage >= 0 && rotatedPage < adapter.getItemCount()
                ? rotatedPage : pageAtCenter();
        rotatedPage = allPages ? -1 : current;
        viewModel.rotatePages(pdfId, allPages ? -1 : current, result -> {
            rotating = false;
            if (binding == null) return;
            if (result == at.woodexplosive.learnarchiver.data.LibraryRepository.RotateResult.OK) {
                renderer.close();
                openDocument(current);
            } else {
                Toast.makeText(requireContext(),
                        result == at.woodexplosive.learnarchiver.data.LibraryRepository.RotateResult.PROTECTED
                                ? R.string.viewer_rotate_protected : R.string.viewer_rotate_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Beobachtet alle PDFs: Wird das offene PDF gelöscht (einzeln, per Mehrfachauswahl oder mit
     * seiner Kategorie), geht der Viewer zurück. Bei Umbenennen wird der Titel aktualisiert.
     */
    private void observePdfStillExists() {
        viewModel.getPdfFiles().observe(getViewLifecycleOwner(), pdfs -> {
            if (pdfs == null || binding == null) return; // noch nicht geladen
            for (PdfFile pdf : pdfs) {
                if (pdf.id == pdfId) {
                    requireActivity().setTitle(pdf.displayName);
                    return;
                }
            }
            Toast.makeText(requireContext(), R.string.viewer_pdf_deleted, Toast.LENGTH_SHORT).show();
            NavHostFragment.findNavController(this).popBackStack();
        });
    }

    private float clampZoom(float z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private void applyZoom(float z, float x, float y) {
        float ratio = z / zoom;
        zoom = z;
        binding.pages.setScaleX(1f);
        binding.pages.setScaleY(1f);

        LinearLayoutManager lm = (LinearLayoutManager) binding.pages.getLayoutManager();
        if (lm == null) return;
        int pos = lm.findFirstVisibleItemPosition();
        View first = lm.findViewByPosition(pos);
        int top = first != null ? first.getTop() : 0;
        int newScrollX = Math.round((binding.hScroll.getScrollX() + x) * ratio - x);

        adapter.setPageWidth(updateRecyclerWidth());

        if (pos != RecyclerView.NO_POSITION) {
            lm.scrollToPositionWithOffset(pos, Math.round(y - ratio * (y - top)));
        }
        binding.hScroll.post(() -> {
            if (binding != null) binding.hScroll.scrollTo(newScrollX, 0);
        });
    }

    /** RecyclerView-Breite = Bildschirm x Zoom setzen; gibt die Seitenbreite (ohne Padding) zurück */
    private int updateRecyclerWidth() {
        int rvWidth = Math.round(binding.hScroll.getWidth() * zoom);
        ViewGroup.LayoutParams lp = binding.pages.getLayoutParams();
        lp.width = rvWidth;
        binding.pages.setLayoutParams(lp);
        return rvWidth - binding.pages.getPaddingLeft() - binding.pages.getPaddingRight();
    }

    /** Untertitel in der Toolbar, z.B. "Treffer für „ableit“" */
    private void setToolbarSubtitle(@Nullable String subtitle) {
        if (requireActivity() instanceof AppCompatActivity) {
            ActionBar bar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
            if (bar != null) bar.setSubtitle(subtitle);
        }
    }

    /**
     * Lesestand frisch aus der DB holen (nicht aus dem TreeItem, das ist evtl. veraltet), dann
     * Zoom setzen, Seiten anzeigen und an die gespeicherte Stelle springen – auch innerhalb der Seite
     * und waagrecht. Bei einem Suchtreffer (startPage) stattdessen oben auf der Trefferseite, ohne Zoom.
     */
    private void restoreReadingPosition(List<Float> aspectRatios) {
        viewModel.getPdf(pdfId, pdf -> {
            if (binding == null) return;
            int page = 0;
            float pageOffset = 0f;
            float scrollX = 0f;
            if (startPage >= 0) {
                page = startPage;
            } else if (pdf != null) {
                page = pdf.lastPage;
                pageOffset = pdf.lastPageOffset;
                scrollX = pdf.lastScrollX;
                zoom = clampZoom(pdf.lastZoom);
            }
            int pageCount = aspectRatios.size();
            if (pageCount == 0) return;
            page = Math.max(0, Math.min(page, pageCount - 1));

            int pageWidth = updateRecyclerWidth();
            adapter.setPages(aspectRatios, pageWidth);
            LinearLayoutManager lm = (LinearLayoutManager) binding.pages.getLayoutManager();
            if (lm != null) lm.scrollToPositionWithOffset(page, -Math.round(pageOffset * pageWidth));
            if (startPage >= 0) revealHit(page);   // aus der Suche: gleich zum Wort

            float sx = scrollX;
            binding.hScroll.post(() -> {
                if (binding != null) binding.hScroll.scrollTo(Math.round(sx * binding.pages.getWidth()), 0);
            });
        });
    }

    /**
     * Lesestand speichern: Seite, wie weit in die Seite gescrollt (in Seitenbreiten, damit es
     * unabhängig von Zoom und Bildschirmbreite passt), Zoom und waagrechte Position.
     * Nur wenn das PDF schon geladen ist – sonst würde Seite 0 gespeichert.
     */
    private void saveReadingPosition() {
        if (binding == null || adapter == null || adapter.getItemCount() == 0) return;
        LinearLayoutManager lm = (LinearLayoutManager) binding.pages.getLayoutManager();
        if (lm == null) return;
        int page = lm.findFirstVisibleItemPosition();
        if (page == RecyclerView.NO_POSITION) return;

        View first = lm.findViewByPosition(page);
        int pageWidth = binding.pages.getWidth() - binding.pages.getPaddingLeft() - binding.pages.getPaddingRight();
        float pageOffset = 0f;
        if (first != null && pageWidth > 0) {
            int scrolledInto = binding.pages.getPaddingTop() - lm.getDecoratedTop(first);
            pageOffset = Math.max(0, scrolledInto) / (float) pageWidth;
        }
        float scrollX = binding.pages.getWidth() > 0
                ? binding.hScroll.getScrollX() / (float) binding.pages.getWidth() : 0f;
        viewModel.saveReadingPosition(pdfId, page, pageOffset, zoom, scrollX);
    }

    // ---------------------------------------------------------------------
    // Springen zwischen Suchtreffern
    // ---------------------------------------------------------------------

    private void setupHitBar(@Nullable String highlight) {
        if (highlight == null || highlight.trim().isEmpty()) return;

        binding.btnNextHit.setOnClickListener(v -> jumpToHit(true));
        binding.btnPrevHit.setOnClickListener(v -> jumpToHit(false));
        binding.btnCloseHits.setOnClickListener(v -> {
            hitsClosed = true;
            binding.hitBar.setVisibility(View.GONE);
        });
        binding.pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {   // User scrollt selbst
                    hitIndex = -1;
                    revealPage = -1;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateHitLabel();
            }
        });

        viewModel.findHitPages(pdfId, highlight, pages -> {
            if (binding == null || pages.isEmpty()) return;
            hitPages = pages;
            // Aus der Suche auf einer Trefferseite geöffnet → das ist der aktuelle Treffer
            if (startPage >= 0) hitIndex = pages.indexOf(startPage);
            binding.hitBar.setVisibility(hitsClosed ? View.GONE : View.VISIBLE);
            updateHitLabel();
        });
    }

    private int currentPage() {
        LinearLayoutManager lm = binding == null ? null : (LinearLayoutManager) binding.pages.getLayoutManager();
        int page = lm == null ? RecyclerView.NO_POSITION : lm.findFirstVisibleItemPosition();
        return page == RecyclerView.NO_POSITION ? 0 : page;
    }

    /** Zur nächsten/vorherigen Trefferseite; am Ende geht es wieder von vorne los */
    private void jumpToHit(boolean forward) {
        if (hitPages.isEmpty() || binding == null) return;
        int n = hitPages.size();
        int next;
        if (hitIndex >= 0) {
            // Vom zuletzt angesprungenen Treffer aus weiter (mit Umlauf)
            next = forward ? (hitIndex + 1) % n : (hitIndex - 1 + n) % n;
        } else {
            // Von der aktuellen Seite aus den nächsten/vorherigen Treffer suchen
            int current = currentPage();
            next = forward ? 0 : n - 1;
            if (forward) {
                for (int i = 0; i < n; i++) {
                    if (hitPages.get(i) > current) { next = i; break; }
                }
            } else {
                for (int i = n - 1; i >= 0; i--) {
                    if (hitPages.get(i) < current) { next = i; break; }
                }
            }
        }
        int target = hitPages.get(next);
        if (target >= adapter.getItemCount()) return;
        hitIndex = next;
        rotatedPage = target;   // die angesprungene Trefferseite ist jetzt die aktuelle (auch fürs Drehen)
        LinearLayoutManager lm = (LinearLayoutManager) binding.pages.getLayoutManager();
        if (lm != null) lm.scrollToPositionWithOffset(target, 0);
        // scrollToPosition löst onScrolled erst nach dem nächsten Layout aus
        binding.pages.post(this::updateHitLabel);
        revealHit(target);
    }

    /**
     * Nach dem Sprung auf eine Trefferseite zum (obersten) markierten Wort scrollen – senkrecht und, wenn
     * eingezoomt, auch waagrecht. Wichtig im Quermodus/eingezoomt: Dort ist eine Seite viel höher als der
     * Bildschirm, oben auf der Seite sähe man das Wort oft gar nicht.
     * Die Positionen kommen vom Renderer (bei Bedarf wird die Seite dafür erst gerendert).
     */
    private void revealHit(int page) {
        if (renderer == null || adapter == null) return;
        revealPage = page;
        PdfDocumentRenderer forRenderer = renderer;
        renderer.findHits(page, adapter.getRenderWidth(), (index, rects) -> {
            // Inzwischen selbst gescrollt, weitergesprungen oder neu geöffnet (Drehen) → nichts tun
            if (binding == null || renderer != forRenderer || revealPage != index || rects.isEmpty()) return;
            revealPage = -1;

            RectF hit = rects.get(0);   // oberster Treffer (bei gleicher Höhe der linke)
            for (RectF r : rects) {
                if (r.top < hit.top - 0.005f || (Math.abs(r.top - hit.top) <= 0.005f && r.left < hit.left)) hit = r;
            }
            RecyclerView rv = binding.pages;
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            if (lm == null || index >= adapter.getItemCount()) return;

            int pageWidth = adapter.getPageWidth();
            int pageHeight = Math.round(pageWidth * adapter.getAspectRatio(index));
            int viewport = rv.getHeight() - rv.getPaddingTop() - rv.getPaddingBottom();
            // Wort etwa ins obere Drittel (darunter liegt die Trefferleiste); liegt es schon weit oben
            // auf der Seite, bleibt der Seitenanfang sichtbar (Offset nie > 0)
            int wordY = Math.round(hit.centerY() * pageHeight);
            int offset = Math.min(0, viewport / 3 - wordY);
            lm.scrollToPositionWithOffset(index, offset);

            // Waagrecht: nur relevant, wenn eingezoomt (sonst kann hScroll nicht scrollen → scrollTo begrenzt)
            int wordX = rv.getPaddingLeft() + Math.round(hit.centerX() * pageWidth);
            binding.hScroll.scrollTo(Math.max(0, wordX - binding.hScroll.getWidth() / 2), 0);
            rv.post(this::updateHitLabel);
        });
    }

    /** "Trefferseite 2 von 5 · S. 7", oder "5 Trefferseiten", wenn die aktuelle Seite keine ist */
    private void updateHitLabel() {
        if (binding == null || hitPages.isEmpty()) return;
        int index = hitIndex >= 0 ? hitIndex : hitPages.indexOf(currentPage());
        binding.hitLabel.setText(index >= 0
                ? getString(R.string.viewer_hit_label, index + 1, hitPages.size(), hitPages.get(index) + 1)
                : getResources().getQuantityString(R.plurals.viewer_hit_label_between, hitPages.size(), hitPages.size()));
    }

    @Override
    public void onResume() {
        super.onResume();
        // In onResume/onPause statt onViewCreated/onDestroyView: Beim Wechsel A -> B pausiert A
        // garantiert VOR B's onResume. Sonst löscht das alte Fragment den Untertitel des neuen.
        setToolbarSubtitle(subtitle);
    }

    @Override
    public void onPause() {
        super.onPause();
        setToolbarSubtitle(null);
        // onPause kommt bei: Zurück, anderes PDF, App in den Hintergrund, Drehung
        saveReadingPosition();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // isRemoving() = Viewer wurde bewusst geschlossen (Zurück) – nicht bei Drehung/App-Ende.
        // Dann beim nächsten Start NICHT automatisch wieder öffnen.
        // Aber nicht, wenn dieser Viewer nur durch einen neuen ersetzt wurde (z.B. dasselbe PDF
        // nochmal aus der Suche geöffnet): Der neue hat sich schon eingetragen und würde sonst gelöscht.
        if (isRemoving() && viewModel != null && !replacedByAnotherViewer()) {
            viewModel.clearLastOpenedPdf(pdfId);
        }
    }

    private boolean replacedByAnotherViewer() {
        try {
            androidx.navigation.NavDestination dest =
                    NavHostFragment.findNavController(this).getCurrentDestination();
            return dest != null && dest.getId() == R.id.pdfViewerFragment;
        } catch (IllegalStateException e) {
            return false;   // kein NavController mehr erreichbar
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_HITS_CLOSED, hitsClosed);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (renderer != null) renderer.close();
        binding = null;
    }
}
