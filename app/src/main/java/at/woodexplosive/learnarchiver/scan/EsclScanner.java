package at.woodexplosive.learnarchiver.scan;

import androidx.annotation.NonNull;

/** Ein gefundener eSCL-Scanner (z.B. HP-Multifunktionsgerät im WLAN) */
public class EsclScanner {
    /** Anzeigename, z.B. "HP DeskJet 4100 series" */
    public final String name;
    /** Basis-URL der eSCL-Schnittstelle, z.B. http://192.168.18.17:8080/eSCL (ohne / am Ende) */
    public final String baseUrl;

    public EsclScanner(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EsclScanner && ((EsclScanner) o).baseUrl.equals(baseUrl);
    }

    @Override
    public int hashCode() {
        return baseUrl.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}
