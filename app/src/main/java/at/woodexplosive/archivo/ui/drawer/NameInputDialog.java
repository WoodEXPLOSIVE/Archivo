package at.woodexplosive.archivo.ui.drawer;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import at.woodexplosive.archivo.R;
import at.woodexplosive.archivo.databinding.DialogNameInputBinding;

/**
 * Wiederverwendbarer Dialog zum Eingeben eines Namens (neue Kategorie, Umbenennen, …).
 * "Speichern" ist nur aktiv, solange ein Name eingegeben ist.
 */
public final class NameInputDialog {
    /** Liefert den eingegebenen Namen (ohne Leerzeichen am Rand) */
    @FunctionalInterface
    public interface OnNameEntered {
        void onNameEntered(@Nullable String name);
    }

    private NameInputDialog() {}

    /**
     * Dialog anzeigen.
     * @param initialText vorbelegter Text (ist markiert), z.B. der alte Name beim Umbenennen
     */
    public static void show(Context context, @StringRes int titleRes,
                            String initialText, OnNameEntered callback) {
        DialogNameInputBinding b = DialogNameInputBinding.inflate(LayoutInflater.from(context));
        b.input.setText(initialText);
        b.input.selectAll();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(b.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, which) ->
                        callback.onNameEntered(b.input.getText() != null ? b.input.getText().toString().trim() : null))
                .create();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();

        Button saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        b.input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                saveBtn.setEnabled(!s.toString().trim().isEmpty());
            }
        });

    }
}
