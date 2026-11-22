package at.woodexplosive.archivo.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import java.util.List;

import at.woodexplosive.archivo.MainActivity;
import at.woodexplosive.archivo.R;
import at.woodexplosive.archivo.data.entity.PdfFile;

/**
 * PDFs an andere Apps schicken (WhatsApp, E-Mail, Drive, ...).
 * Die Dateien liegen privat in files/pdfs/ – andere Apps dürfen dort nicht lesen. Deshalb
 * werden sie mit ihrem lesbaren Namen in cache/share/ kopiert und über einen FileProvider
 * mit befristeter Leseberechtigung (content://-Uri) weitergegeben.
 */
public final class ShareHelper {

    private ShareHelper() {}

    /**
     * PDFs über das Android-Teilen-Menü weitergeben (eines oder mehrere, Archivo selbst ausgeblendet).
     * Bei leerer Liste oder Fehler erscheint ein Toast.
     */
    public static void share(Activity activity, LibraryViewModel viewModel, List<PdfFile> pdfs) {
        if (pdfs.isEmpty()) {
            Toast.makeText(activity, R.string.share_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.prepareShare(pdfs, uris -> {
            if (activity.isFinishing()) return;
            if (uris.isEmpty()) {
                Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_LONG).show();
                return;
            }
            Intent send;
            if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            send.setType("application/pdf");

            // Die Leseberechtigung gilt nur für Uris im ClipData – also alle eintragen
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            send.setClipData(clip);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            int n = uris.size();
            Intent chooser = Intent.createChooser(send,
                    activity.getResources().getQuantityString(R.plurals.share_chooser_title, n, n));
            // Unsere eigene App (kann ja PDFs empfangen) nicht als Ziel anbieten
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                        new ComponentName[]{new ComponentName(activity, MainActivity.class)});
            }
            activity.startActivity(chooser);
        });
    }
}
