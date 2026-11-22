package at.woodexplosive.learnarchiver.ui.scan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import at.woodexplosive.learnarchiver.R;
import at.woodexplosive.learnarchiver.databinding.FragmentScanBinding;
import at.woodexplosive.learnarchiver.scan.EsclClient;
import at.woodexplosive.learnarchiver.scan.EsclScanner;
import at.woodexplosive.learnarchiver.scan.JpegPdfWriter;
import at.woodexplosive.learnarchiver.ui.LibraryViewModel;
import at.woodexplosive.learnarchiver.ui.drawer.NameInputDialog;

/** Scannen von einem Netzwerk-Scanner (eSCL) direkt in eine Kategorie */
public class ScanFragment extends Fragment {

    public static final String ARG_CATEGORY_ID = "categoryId";
    public static final String ARG_CATEGORY_NAME = "categoryName";

    /** Ab Android 17 braucht Zugriff aufs Heimnetz eine Laufzeit-Berechtigung */
    private static final int API_LOCAL_NETWORK_PERMISSION = 37;

    private FragmentScanBinding binding;
    private ScanViewModel scanVm;
    private LibraryViewModel libraryVm;
    private long categoryId;
    private ScanPageAdapter pageAdapter;

    private final ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    scanVm.startDiscovery();
                } else if (binding != null) {
                    binding.discoveryProgress.setVisibility(View.GONE);
                    binding.discoveryHint.setText(R.string.scan_permission_denied);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().setTitle(R.string.scan_title);

        Bundle args = requireArguments();
        categoryId = args.getLong(ARG_CATEGORY_ID);
        binding.scanTarget.setText(getString(R.string.scan_target, args.getString(ARG_CATEGORY_NAME, "")));

        scanVm = new ViewModelProvider(this).get(ScanViewModel.class);
        libraryVm = new ViewModelProvider(requireActivity()).get(LibraryViewModel.class);

        if (savedInstanceState == null) {
            String now = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date());
            binding.nameInput.setText(getString(R.string.scan_default_name, now));
        }

        startDiscoveryWithPermission();

        scanVm.getScanners().observe(getViewLifecycleOwner(), this::showScanners);
        scanVm.getSelected().observe(getViewLifecycleOwner(), s -> showScanners(scanVm.getScanners().getValue()));
        scanVm.getCapabilities().observe(getViewLifecycleOwner(), this::applyCapabilities);
        scanVm.getState().observe(getViewLifecycleOwner(), this::render);

        binding.scannerGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof EsclScanner) scanVm.select((EsclScanner) rb.getTag());
        });

        binding.btnManualIp.setOnClickListener(v ->
                NameInputDialog.show(requireContext(), R.string.scan_manual_ip_title, "", scanVm::addManual));

        binding.btnScan.setOnClickListener(v -> {
            ScanViewModel.State s = scanVm.getState().getValue();
            if (s != null && s.busy) {
                scanVm.cancelScan();
            } else {
                scanVm.scan(selectedSource(), selectedColor(), selectedDpi(), selectedDuplex(),
                        binding.switchRemoveBlank.isChecked());
            }
        });
        binding.btnDiscard.setOnClickListener(v -> scanVm.discardPages());
        binding.btnFinish.setOnClickListener(v -> scanVm.finish());

        setupPreview();

        binding.switchDuplex.setOnCheckedChangeListener((b, checked) ->
                updateDuplexVisibility(scanVm.getCapabilities().getValue()));

        // Duplex-Schalter nur beim Einzug zeigen
        binding.toggleSource.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateDuplexVisibility(scanVm.getCapabilities().getValue());
        });
    }

    /** "Beidseitig" gibt es nur, wenn das Gerät Duplex kann und der Einzug gewählt ist */
    private void updateDuplexVisibility(@Nullable EsclClient.Capabilities caps) {
        if (binding == null) return;
        boolean show = caps != null && caps.duplex
                && binding.toggleSource.getCheckedButtonId() == R.id.source_feeder;
        binding.switchDuplex.setVisibility(show ? View.VISIBLE : View.GONE);
        // "Leere Rückseiten entfernen" nur, wenn beidseitig gescannt wird
        binding.switchRemoveBlank.setVisibility(show && binding.switchDuplex.isChecked() ? View.VISIBLE : View.GONE);
    }

    /** Vorschau-Leiste: antippen = groß anzeigen, ✕ = entfernen, gedrückt halten + ziehen = umsortieren */
    private void setupPreview() {
        int thumbHeight = getResources().getDimensionPixelSize(R.dimen.scan_thumb_height);
        pageAdapter = new ScanPageAdapter(thumbHeight, new ScanPageAdapter.Listener() {
            @Override
            public void onPageClick(int position, JpegPdfWriter.Page page) {
                showPageDialog(position, page);
            }

            @Override
            public void onPageRotate(int position) {
                scanVm.rotatePage(position);
            }

            @Override
            public void onPageDelete(int position) {
                scanVm.removePage(position);
            }
        });
        binding.pageList.setAdapter(pageAdapter);

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            private int dragFrom = RecyclerView.NO_POSITION;
            private int dragTo = RecyclerView.NO_POSITION;

            @Override
            public boolean isLongPressDragEnabled() {
                return pageAdapter.isEnabled();
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition();
                int b = to.getBindingAdapterPosition();
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false;
                if (dragFrom == RecyclerView.NO_POSITION) dragFrom = a;
                dragTo = b;
                pageAdapter.moveLocal(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                // Erst beim Loslassen die neue Reihenfolge ans ViewModel geben
                if (dragFrom != RecyclerView.NO_POSITION && dragFrom != dragTo) {
                    scanVm.movePage(dragFrom, dragTo);
                }
                dragFrom = dragTo = RecyclerView.NO_POSITION;
                rv.post(pageAdapter::refreshNumbers);   // Nummern 1, 2, 3 … neu setzen
            }
        }).attachToRecyclerView(binding.pageList);
    }

    /** Eine gescannte Seite groß anzeigen – mit Drehen (Dialog bleibt offen) und Entfernen */
    private void showPageDialog(int position, JpegPdfWriter.Page page) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        View view = getLayoutInflater().inflate(R.layout.dialog_scan_page, null);
        ImageView image = view.findViewById(R.id.page_full);
        image.setMaxHeight(Math.round(dm.heightPixels * 0.7f));
        // Breite des Bildschirms reicht als Auflösung, die Höhe begrenzt maxHeight
        JpegPdfWriter.Page[] shown = {page};
        Bitmap bmp = ScanPageAdapter.decodePage(page, dm.widthPixels, 1);
        if (bmp != null) image.setImageBitmap(bmp);

        int count = pageAdapter.getItemCount();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.scan_preview_title, position + 1, count))
                .setView(view)
                .setPositiveButton(R.string.close, null);
        boolean editable = pageAdapter.isEnabled();
        if (editable) {
            builder.setNegativeButton(R.string.scan_preview_remove, (d, w) -> scanVm.removePage(position));
            builder.setNeutralButton(R.string.scan_preview_rotate_button, null);
        }
        androidx.appcompat.app.AlertDialog dialog = builder.show();
        if (editable) {
            // Eigener Klick-Listener erst nach show(): sonst schließt der Knopf den Dialog
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
                scanVm.rotatePage(position);
                shown[0] = shown[0].rotatedClockwise();
                Bitmap rotated = ScanPageAdapter.decodePage(shown[0], dm.widthPixels, 1);
                if (rotated != null) image.setImageBitmap(rotated);
            });
        }
    }

    private void startDiscoveryWithPermission() {
        if (Build.VERSION.SDK_INT >= API_LOCAL_NETWORK_PERMISSION
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
        } else {
            scanVm.startDiscovery();
        }
    }

    // ---------------------------------------------------------------------
    // Anzeige
    // ---------------------------------------------------------------------

    private void showScanners(@Nullable List<EsclScanner> scanners) {
        if (binding == null || scanners == null) return;
        EsclScanner selected = scanVm.getSelected().getValue();

        binding.scannerGroup.setOnCheckedChangeListener(null);   // beim Neuaufbau keine Klicks auslösen
        android.widget.RadioGroup.OnCheckedChangeListener listener = (group, checkedId) -> {
            View rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof EsclScanner) scanVm.select((EsclScanner) rb.getTag());
        };
        binding.scannerGroup.removeAllViews();
        for (EsclScanner s : scanners) {
            RadioButton rb = new RadioButton(requireContext());
            rb.setId(View.generateViewId());
            rb.setText(s.name);
            rb.setTag(s);
            binding.scannerGroup.addView(rb);
            if (s.equals(selected)) rb.setChecked(true);
        }
        binding.scannerGroup.setOnCheckedChangeListener(listener);

        boolean none = scanners.isEmpty();
        binding.discoveryHint.setText(none ? R.string.scan_searching : R.string.scan_found_hint);
        binding.discoveryProgress.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    /** Einstellungen an das Gerät anpassen: z.B. "Einzug" nur zeigen, wenn es einen gibt */
    private void applyCapabilities(@Nullable EsclClient.Capabilities caps) {
        if (binding == null) return;
        binding.settingsPanel.setVisibility(caps == null ? View.GONE : View.VISIBLE);
        if (caps == null) return;

        boolean platen = caps.platen || !caps.adf;   // ohne Angabe: Glas annehmen
        binding.sourcePlaten.setVisibility(platen ? View.VISIBLE : View.GONE);
        binding.sourceFeeder.setVisibility(caps.adf ? View.VISIBLE : View.GONE);
        if (binding.toggleSource.getCheckedButtonId() == View.NO_ID
                || (!caps.adf && binding.toggleSource.getCheckedButtonId() == R.id.source_feeder)) {
            // Einzug vorauswählen, wenn vorhanden – mehrere Seiten auf einmal
            binding.toggleSource.check(caps.adf ? R.id.source_feeder : R.id.source_platen);
        }

        boolean gray = caps.colorModes.isEmpty() || caps.colorModes.contains(EsclClient.COLOR_GRAY);
        boolean rgb = caps.colorModes.isEmpty() || caps.colorModes.contains(EsclClient.COLOR_RGB);
        binding.colorGray.setVisibility(gray ? View.VISIBLE : View.GONE);
        binding.colorRgb.setVisibility(rgb ? View.VISIBLE : View.GONE);
        if (binding.toggleColor.getCheckedButtonId() == View.NO_ID) {
            binding.toggleColor.check(gray ? R.id.color_gray : R.id.color_rgb);   // Graustufen: kleiner, reicht für Text
        }

        updateDuplexVisibility(caps);

        setDpiVisible(binding.dpi150, caps, 150);
        setDpiVisible(binding.dpi200, caps, 200);
        setDpiVisible(binding.dpi300, caps, 300);
        if (binding.toggleDpi.getCheckedButtonId() == View.NO_ID) {
            binding.toggleDpi.check(supports(caps, 200) ? R.id.dpi_200
                    : supports(caps, 300) ? R.id.dpi_300 : R.id.dpi_150);
        }

        // postValue() verschiedener LiveData kann in anderer Reihenfolge ankommen als gesendet:
        // Der Status ("Bereit") war evtl. schon da, als noch keine Fähigkeiten bekannt waren
        // → Knöpfe jetzt neu berechnen, sonst bleibt "Scannen" ausgegraut.
        ScanViewModel.State s = scanVm.getState().getValue();
        if (s != null && s.resultPdf == null) render(s);
    }

    private void setDpiVisible(View button, EsclClient.Capabilities caps, int dpi) {
        button.setVisibility(supports(caps, dpi) ? View.VISIBLE : View.GONE);
    }

    private static boolean supports(EsclClient.Capabilities caps, int dpi) {
        return caps.resolutions.isEmpty() || caps.resolutions.contains(dpi);
    }

    private void render(ScanViewModel.State s) {
        if (binding == null) return;
        boolean hasScanner = scanVm.getCapabilities().getValue() != null;

        binding.scanProgress.setVisibility(s.busy ? View.VISIBLE : View.INVISIBLE);
        binding.scanStatus.setText(s.message);
        binding.scanStatus.setTextColor(s.error
                ? requireContext().getColor(android.R.color.holo_red_dark)
                : binding.scanTarget.getCurrentTextColor());

        // Während des Scans wird der Scan-Knopf zu "Abbrechen"
        binding.btnScan.setText(s.busy ? R.string.cancel : R.string.scan_button);
        binding.btnScan.setEnabled(hasScanner || s.busy);
        binding.btnFinish.setEnabled(!s.busy && s.pageCount > 0);
        binding.btnDiscard.setEnabled(!s.busy && s.pageCount > 0);
        binding.btnFinish.setText(s.pageCount > 0
                ? getResources().getQuantityString(R.plurals.scan_finish_pages, s.pageCount, s.pageCount)
                : getString(R.string.scan_finish));

        // Vorschau: ab der ersten Seite sichtbar; während Scan/Speichern nicht bearbeitbar
        binding.previewPanel.setVisibility(s.pages.isEmpty() ? View.GONE : View.VISIBLE);
        if (pageAdapter != null && s.resultPdf == null) {
            pageAdapter.setPages(s.pages);
            pageAdapter.setEnabled(!s.busy);
        }

        if (s.resultPdf != null) saveResult(s);
    }

    private void saveResult(ScanViewModel.State s) {
        String name = binding.nameInput.getText() == null ? "" : binding.nameInput.getText().toString().trim();
        if (name.isEmpty()) name = getString(R.string.scan_default_name, "");
        int pages = s.pageCount;
        libraryVm.saveScan(categoryId, name, s.resultPdf, ok -> {
            if (!isAdded()) return;
            Toast.makeText(requireContext(), ok
                    ? getResources().getQuantityString(R.plurals.scan_saved, pages, pages)
                    : getString(R.string.scan_error_generic), Toast.LENGTH_LONG).show();
            if (ok) NavHostFragment.findNavController(this).popBackStack();
        });
        scanVm.consumeResult();
    }

    // ---------------------------------------------------------------------

    private String selectedSource() {
        return binding.toggleSource.getCheckedButtonId() == R.id.source_feeder
                ? EsclClient.SOURCE_FEEDER : EsclClient.SOURCE_PLATEN;
    }

    private boolean selectedDuplex() {
        return binding.switchDuplex.getVisibility() == View.VISIBLE && binding.switchDuplex.isChecked();
    }

    private String selectedColor() {
        return binding.toggleColor.getCheckedButtonId() == R.id.color_rgb
                ? EsclClient.COLOR_RGB : EsclClient.COLOR_GRAY;
    }

    private int selectedDpi() {
        int id = binding.toggleDpi.getCheckedButtonId();
        return id == R.id.dpi_150 ? 150 : id == R.id.dpi_300 ? 300 : 200;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pageAdapter != null) pageAdapter.shutdown();
        pageAdapter = null;
        binding = null;
    }
}
