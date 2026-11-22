package at.woodexplosive.learnarchiver;

import android.os.Build;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import at.woodexplosive.learnarchiver.data.enitity.PdfFile;
import at.woodexplosive.learnarchiver.databinding.ActivityMainBinding;
import at.woodexplosive.learnarchiver.ui.LibraryViewModel;
import at.woodexplosive.learnarchiver.ui.drawer.DrawerTreeAdapter;
import at.woodexplosive.learnarchiver.ui.drawer.NameInputDialog;
import at.woodexplosive.learnarchiver.ui.drawer.SearchResult;
import at.woodexplosive.learnarchiver.ui.drawer.SearchResultAdapter;
import at.woodexplosive.learnarchiver.ui.drawer.TreeItem;
import at.woodexplosive.learnarchiver.ui.scan.ScanFragment;
import at.woodexplosive.learnarchiver.ui.viewer.PdfViewerFragment;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private LibraryViewModel viewModel;
    private NavController navController;
    private SearchResultAdapter searchAdapter;
    private int pendingIndexCount = 0;

    /** Ergebnis des Kamera-Scanners (ML Kit Document Scanner) */
    private final ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> cameraScan =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    viewModel.takePendingImportCategoryId();   // abgebrochen
                    return;
                }
                com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult scan =
                        com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
                                .fromActivityResultIntent(result.getData());
                if (scan == null || scan.getPdf() == null) return;
                onCameraScanResult(scan.getPdf().getUri(), scan.getPdf().getPageCount());
            });

    private final ActivityResultLauncher<String[]> pickPdfs =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> viewModel.importPdfs(uris, this::showImportResult));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment navHost = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHost != null) navController = navHost.getNavController();

        setSupportActionBar(binding.toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar,
                R.string.drawer_open, R.string.drawer_close);
        binding.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    if (viewModel.isSelectionMode()) {
                        viewModel.clearSelection();
                    } else {
                        binding.drawerLayout.closeDrawer(GravityCompat.START);
                    }
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        viewModel = new ViewModelProvider(this).get(LibraryViewModel.class);

        DrawerTreeAdapter adapter = new DrawerTreeAdapter(new DrawerTreeAdapter.Listener() {
            @Override
            public void onItemClick(TreeItem item) {
                if (viewModel.isSelectionMode()) {
                    viewModel.toggleSelection(item);
                } else if (item.type == TreeItem.TYPE_CATEGORY) {
                    viewModel.toggleExpanded(item.id);
                } else {
                    if (item.pdf != null) openPdf(item.pdf);
                }
            }

            @Override
            public void onItemLongClick(TreeItem item, View anchor) {
                if (viewModel.isSelectionMode()) {
                    viewModel.toggleSelection(item);
                } else if (item.type == TreeItem.TYPE_CATEGORY) {
                    showCategoryMenu(item, anchor);
                } else if (item.type == TreeItem.TYPE_PDF) {
                    showPdfMenu(item, anchor);
                }
            }

            @Override
            public void onExpandClick(TreeItem item) {
                viewModel.toggleExpanded(item.id);
            }
        });

        binding.btnAddCategory.setOnClickListener(v ->
                NameInputDialog.show(this, R.string.new_category, "",
                        name -> viewModel.addCategory(name, null)));
        binding.drawerList.setLayoutManager(new LinearLayoutManager(this));
        binding.drawerList.setAdapter(adapter);
        viewModel.getTree().observe(this, adapter::submitList);

        // Über "Teilen" gestartet? Dann PDFs übernehmen statt das letzte PDF zu öffnen
        boolean shared = savedInstanceState == null && handleShareIntent(getIntent());

        // Beim echten App-Start (nicht nach Drehung) das zuletzt offene PDF wieder öffnen
        if (savedInstanceState == null && !shared) {
            viewModel.getLastOpenedPdf(pdf -> {
                if (pdf != null && !isFinishing() && !getSupportFragmentManager().isStateSaved()) {
                    openPdf(pdf);
                }
            });
        }

        setupSearch();

        // ---------- Mehrfachauswahl ----------
        viewModel.getSelectionMode().observe(this, active -> {
            binding.headerNormal.setVisibility(active ? View.GONE : View.VISIBLE);
            binding.headerSelection.setVisibility(active ? View.VISIBLE : View.GONE);
            binding.searchPanel.setVisibility(active ? View.GONE : View.VISIBLE);
        });
        viewModel.getSelectedCount().observe(this, count -> {
            binding.selectionCount.setText(
                    getResources().getQuantityString(R.plurals.selected_count, count, count));
            binding.btnMoveSelection.setEnabled(count > 0);
            binding.btnDeleteSelection.setEnabled(count > 0);
            binding.btnShareSelection.setEnabled(count > 0);
        });
        binding.btnCloseSelection.setOnClickListener(v -> viewModel.clearSelection());
        binding.btnSelectAll.setOnClickListener(v -> viewModel.selectAllVisible());
        binding.btnMoveSelection.setOnClickListener(v -> showBulkMoveDialog());
        binding.btnDeleteSelection.setOnClickListener(v -> confirmBulkDelete());
        binding.btnShareSelection.setOnClickListener(v -> {
            at.woodexplosive.learnarchiver.ui.ShareHelper.share(this, viewModel, viewModel.getSelectedPdfsDeep());
            viewModel.clearSelection();
        });

        // Seitenleiste zu -> Auswahl beenden
        binding.drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(View drawerView) {
                viewModel.clearSelection();
                hideKeyboard();
            }
        });

        //debug
//        AppDataBase db = AppDataBase.getInstance(this);
//        AppDataBase.dbExecutor.execute(() -> {
//            long mathe = db.categoryDao().insert(new Category("Mathe", null));
//            long analysis = db.categoryDao().insert(new Category("Analysis", mathe));
//            db.categoryDao().insert(new Category("Geometrie", mathe));
//            db.categoryDao().insert(new Category("Englisch", null));
//            db.pdfFileDao().insert(new PdfFile(analysis, "Ableitungen.pdf", "dummy1.pdf"));
//            db.pdfFileDao().insert(new PdfFile(mathe, "Formelsammlung.pdf", "dummy2.pdf"));
//        });
    }

    private void showCategoryMenu(TreeItem item, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.menu_category);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true);
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_add_subcategory) {
                NameInputDialog.show(this, R.string.new_subcategory, "",
                        name -> viewModel.addCategory(name, item.id));
            } else if (id == R.id.action_rename) {
                NameInputDialog.show(this, R.string.rename, item.name,
                        name -> viewModel.renameCategory(item.id, name));
            } else if (id == R.id.action_delete) {
                confirmDeleteCategory(item);
            } else if (id == R.id.action_import_pdf) {
                viewModel.setPendingImportCategoryId(item.id);
                pickPdfs.launch(new String[]{"application/pdf"});
            } else if (id == R.id.action_scan) {
                openScan(item);
            } else if (id == R.id.action_scan_camera) {
                startCameraScan(item.id);
            } else if (id == R.id.action_move) {
                showMoveCategoryDialog(item);
            } else if (id == R.id.action_select) {
                viewModel.startSelection(item);
            } else {
                return false;
            }
            return true;
        });
        popup.show();
    }

    private void showPdfMenu(TreeItem item, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.menu_pdf);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true);
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_rename) {
                NameInputDialog.show(this, R.string.rename, item.name,
                        name -> viewModel.renamePdf(item.id, name));
            } else if (id == R.id.action_delete) {
                confirmDeletePdf(item);
            } else if (id == R.id.action_move) {
                showMoveDialog(item);
            } else if (id == R.id.action_share) {
                if (item.pdf != null) {
                    at.woodexplosive.learnarchiver.ui.ShareHelper.share(this, viewModel,
                            Collections.singletonList(item.pdf));
                }
            } else if (id == R.id.action_select) {
                viewModel.startSelection(item);
            } else {
                return false;
            }
            return true;
        });
        popup.show();
    }

    private void confirmDeleteCategory(TreeItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_category_title)
                .setMessage(getString(R.string.delete_category_message, item.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, which) -> viewModel.deleteCategory(item.id))
                .show();
    }

    private void confirmDeletePdf(TreeItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_pdf_title)
                .setMessage(getString(R.string.delete_pdf_message, item.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, which) -> viewModel.deletePdf(item.pdf))
                .show();
    }

    private void showImportResult(int imported, int failed) {
        String msg = getResources().getQuantityString(R.plurals.pdfs_imported, imported, imported);
        if (failed > 0) {
            msg += "\n" + getResources().getQuantityString(R.plurals.pdfs_failed, failed, failed);
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // =====================================================================
    // Scannen
    // =====================================================================

    private void openScan(TreeItem category) {
        Bundle args = new Bundle();
        args.putLong(ScanFragment.ARG_CATEGORY_ID, category.id);
        args.putString(ScanFragment.ARG_CATEGORY_NAME, category.name);
        NavOptions options = new NavOptions.Builder()
                .setPopUpTo(R.id.homeFragment, false)
                .build();
        navController.navigate(R.id.scanFragment, args, options);
        binding.drawerLayout.closeDrawer(GravityCompat.START);
    }

    // =====================================================================
    // Teilen -> Archivo
    // =====================================================================

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** @return true, wenn es ein Teilen-Intent war */
    private boolean handleShareIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return false;

        List<Uri> uris = sharedUris(intent);
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_no_pdf, Toast.LENGTH_LONG).show();
            return true;
        }
        // Kategorien kommen asynchron aus der DB → erst danach fragen, wohin
        viewModel.whenCategoriesLoaded(() -> showShareTargetDialog(uris));
        return true;
    }

    @SuppressWarnings("deprecation")   // typsichere Varianten erst ab API 33
    private static List<Uri> sharedUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        // Manche Apps liefern die Dateien nur über ClipData
        ClipData clip = intent.getClipData();
        if (uris.isEmpty() && clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        return uris;
    }

    /** Fragt, in welche Kategorie die geteilten PDFs sollen (oder legt eine neue an) */
    private void showShareTargetDialog(List<Uri> uris) {
        int n = uris.size();
        chooseCategory(getResources().getQuantityString(R.plurals.share_import_title, n, n),
                id -> importShared(id, uris));
    }

    /** Kategorie wählen oder neu anlegen; onChosen bekommt die Kategorie-ID */
    private void chooseCategory(String title, at.woodexplosive.learnarchiver.data.LibraryRepository.ResultCallback<Long> onChosen) {
        List<TreeItem> categories = viewModel.getAllCategoriesFlat();
        String[] labels = new String[categories.size() + 1];
        labels[0] = getString(R.string.share_new_category);
        for (int i = 0; i < categories.size(); i++) {
            labels[i + 1] = indentedLabel(categories.get(i));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        NameInputDialog.show(this, R.string.new_category, "",
                                name -> viewModel.addCategory(name, null, onChosen));
                    } else {
                        onChosen.onResult(categories.get(which - 1).id);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // =====================================================================
    // Kamera-Scan (ML Kit Document Scanner)
    // =====================================================================

    /**
     * Startet Googles Dokumentenscanner: erkennt die Blattkanten, entzerrt, bietet Filter
     * und mehrere Seiten – und liefert am Ende direkt ein PDF.
     */
    private void startCameraScan(long categoryId) {
        viewModel.setPendingImportCategoryId(categoryId);
        com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions options =
                new com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit(100)
                        .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                        .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build();
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener(sender -> cameraScan.launch(
                        new androidx.activity.result.IntentSenderRequest.Builder(sender).build()))
                .addOnFailureListener(e -> {
                    viewModel.takePendingImportCategoryId();
                    Toast.makeText(this, R.string.scan_camera_unavailable, Toast.LENGTH_LONG).show();
                });
        binding.drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void onCameraScanResult(Uri pdfUri, int pageCount) {
        Long categoryId = viewModel.takePendingImportCategoryId();
        if (categoryId != null) {
            saveCameraScan(categoryId, pdfUri, pageCount);
        } else {
            // App wurde während des Scannens vom System beendet → Ziel neu erfragen
            viewModel.whenCategoriesLoaded(() -> chooseCategory(getString(R.string.scan_camera_choose_category),
                    id -> saveCameraScan(id, pdfUri, pageCount)));
        }
    }

    private void saveCameraScan(long categoryId, Uri pdfUri, int pageCount) {
        String now = ScanFragment.formatScanDate();
        String name = getString(R.string.scan_default_name, now);
        if ("file".equals(pdfUri.getScheme()) && pdfUri.getPath() != null) {
            viewModel.saveScan(categoryId, name, new java.io.File(pdfUri.getPath()), ok -> {
                Toast.makeText(this, ok
                        ? getResources().getQuantityString(R.plurals.scan_saved, pageCount, pageCount)
                        : getString(R.string.scan_error_generic), Toast.LENGTH_LONG).show();
                if (ok) binding.drawerLayout.openDrawer(GravityCompat.START);
            });
        } else {
            importShared(categoryId, Collections.singletonList(pdfUri));   // content:// → normaler Import
        }
    }

    private void importShared(long categoryId, List<Uri> uris) {
        viewModel.setPendingImportCategoryId(categoryId);
        viewModel.importPdfs(uris, this::showImportResult);
        binding.drawerLayout.openDrawer(GravityCompat.START);   // Ergebnis gleich sichtbar
    }

    private void openPdf(PdfFile pdf) {
        openPdf(pdf, -1);
    }

    /** @param startPage Seite, auf der geöffnet wird (0-basiert); -1 = gespeicherter Lesestand */
    private void openPdf(PdfFile pdf, int startPage) {
        openPdf(pdf, startPage, null);
    }

    /** @param highlight Suchbegriff, der im Viewer markiert wird (null = keiner) */
    private void openPdf(PdfFile pdf, int startPage, String highlight) {
        Bundle args = new Bundle();
        args.putString(PdfViewerFragment.ARG_HIGHLIGHT, highlight);
        args.putLong(PdfViewerFragment.ARG_PDF_ID, pdf.id);
        args.putInt(PdfViewerFragment.ARG_START_PAGE, startPage);
        args.putString(PdfViewerFragment.ARG_FILE_NAME, pdf.filePath);
        args.putString(PdfViewerFragment.ARG_TITLE, pdf.displayName);

        NavOptions options = new NavOptions.Builder()
                .setPopUpTo(R.id.homeFragment, false)
                .build();

        navController.navigate(R.id.pdfViewerFragment, args, options);
        binding.drawerLayout.closeDrawer(GravityCompat.START);
    }

    // =====================================================================
    // Suche
    // =====================================================================

    private void setupSearch() {
        searchAdapter = new SearchResultAdapter(this::onSearchResultClick);
        binding.searchList.setLayoutManager(new LinearLayoutManager(this));
        binding.searchList.setAdapter(searchAdapter);

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                viewModel.setSearchQuery(s.toString());
                updateSearchVisibility();
            }
        });
        // "Suchen"-Taste auf der Tastatur: nur Tastatur schließen
        binding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });

        binding.chipContent.setChecked(viewModel.isContentSearch());
        binding.chipContent.setOnCheckedChangeListener((chip, checked) -> {
            viewModel.setContentSearch(checked);
            updateSearchInfo();
        });

        viewModel.getSearchResults().observe(this, results -> {
            searchAdapter.submitList(results);
            updateSearchVisibility();
        });
        viewModel.getPendingIndexCount().observe(this, count -> {
            pendingIndexCount = count == null ? 0 : count;
            updateSearchInfo();
        });
        updateSearchInfo();
        updateSearchVisibility();
    }

    /** Baum oder Trefferliste anzeigen – je nachdem, ob gerade gesucht wird */
    private void updateSearchVisibility() {
        boolean searching = !viewModel.getSearchQuery().isEmpty()
                || binding.searchInput.getText() != null && binding.searchInput.getText().toString().trim().length() > 0;
        List<SearchResult> results = viewModel.getSearchResults().getValue();
        boolean empty = results == null || results.isEmpty();

        binding.drawerList.setVisibility(searching ? View.GONE : View.VISIBLE);
        binding.searchList.setVisibility(searching && !empty ? View.VISIBLE : View.GONE);
        binding.searchEmpty.setVisibility(searching && empty ? View.VISIBLE : View.GONE);
    }

    /** Hinweis unter dem Chip: Indizierung läuft noch */
    private void updateSearchInfo() {
        String info = null;
        if (binding.chipContent.isChecked() && pendingIndexCount > 0) {
            info = getResources().getQuantityString(
                    R.plurals.search_indexing_pending, pendingIndexCount, pendingIndexCount);
        }
        binding.searchInfo.setText(info);
        binding.searchInfo.setVisibility(info == null ? View.GONE : View.VISIBLE);
    }

    private void onSearchResultClick(SearchResult result) {
        hideKeyboard();
        if (result.type == SearchResult.TYPE_PDF && result.pdf != null) {
            // Bei Inhaltssuche den Begriff im PDF gelb markieren
            String highlight = viewModel.isContentSearch() ? viewModel.getSearchQuery() : null;
            openPdf(result.pdf, result.page, highlight);
        } else {
            // Kategorie: Suche beenden und die Kategorie im Baum aufklappen
            binding.searchInput.setText("");
            viewModel.revealCategory(result.id);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(binding.searchInput.getWindowToken(), 0);
        binding.searchInput.clearFocus();
    }

    private void showMoveDialog(TreeItem item) {
        if (item.pdf == null) return;
        List<TreeItem> categories = viewModel.getAllCategoriesFlat();
        String[] labels = new String[categories.size()];
        int checked = -1;
        for (int i = 0; i < categories.size(); i++) {
            TreeItem c = categories.get(i);
            labels[i] = indentedLabel(c);

            if (c.id == item.pdf.categoryId) {
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.move_pdf_title, item.name))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    long target = categories.get(which).id;
                    if (target != item.pdf.categoryId) {
                        viewModel.movePdf(item.id, target);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showMoveCategoryDialog(TreeItem item) {
        // Sich selbst + eigene Unterkategorien ausschlie\u00dfen \u2192 sonst entst\u00fcnde eine Schleife im Baum
        List<TreeItem> categories = viewModel.getAllCategoriesFlat(Collections.singleton(item.id));
        Long currentParent = viewModel.getParentId(item.id);

        // Eintrag 0 = "Oberste Ebene" (parentId null), danach alle erlaubten Kategorien um eine Ebene einger\u00fcckt
        String[] labels = new String[categories.size() + 1];
        labels[0] = getString(R.string.top_level);
        int checked = currentParent == null ? 0 : -1;

        for (int i = 0; i < categories.size(); i++) {
            TreeItem c = categories.get(i);
            labels[i + 1] = "\u2003\u2003" + indentedLabel(c);
            if (currentParent != null && c.id == currentParent) {
                checked = i + 1;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.move_category_title, item.name))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Long target = which == 0 ? null : categories.get(which - 1).id;
                    boolean unchanged = target == null ? currentParent == null : target.equals(currentParent);
                    if (!unchanged) {
                        viewModel.moveCategory(item.id, target);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showBulkMoveDialog() {
        LibraryViewModel.Selection sel = viewModel.getEffectiveSelection();
        if (sel.isEmpty()) return;

        // Ausgewaehlte Kategorien + deren Unterbaeume sind keine gueltigen Ziele (Schleifen-Sperre)
        List<TreeItem> categories = viewModel.getAllCategoriesFlat(sel.categoryIds);
        // "Oberste Ebene" nur, wenn keine PDFs dabei sind - PDFs brauchen immer eine Kategorie
        boolean allowTopLevel = sel.pdfIds.isEmpty();
        int offset = allowTopLevel ? 1 : 0;

        String[] labels = new String[categories.size() + offset];
        if (allowTopLevel) labels[0] = getString(R.string.top_level);
        for (int i = 0; i < categories.size(); i++) {
            labels[i + offset] = (allowTopLevel ? "\u2003\u2003" : "") + indentedLabel(categories.get(i));
        }

        int total = sel.size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(getResources().getQuantityString(R.plurals.move_selection_title, total, total))
                .setItems(labels, (dialog, which) -> {
                    Long target = (allowTopLevel && which == 0) ? null : categories.get(which - offset).id;
                    viewModel.moveSelection(target);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmBulkDelete() {
        int total = viewModel.getEffectiveSelection().size();
        if (total == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_selection_title)
                .setMessage(getResources().getQuantityString(R.plurals.delete_selection_message, total, total))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, which) -> viewModel.deleteSelection())
                .show();
    }

    private String indentedLabel(TreeItem c) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < c.depth; j++) {
            sb.append("\u2003\u2003");
        }
        sb.append(c.name);
        return sb.toString();
    }
}