package at.woodexplosive.archivo.scan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Findet eSCL-Scanner im WLAN per mDNS/DNS-SD (Dienst "_uscan._tcp" – so melden sich
 * AirScan-/Mopria-fähige Geräte an). Android bringt dafür den NsdManager mit.
 * <p>
 * Gefundene Dienste müssen erst "aufgelöst" werden (IP, Port, Zusatzinfos). Vor Android 14
 * darf immer nur EINE Auflösung gleichzeitig laufen → Warteschlange.
 * Callbacks kommen auf einem Hintergrund-Thread!
 */
public class ScannerDiscovery {

    private static final String TAG = "ScannerDiscovery";
    private static final String SERVICE_TYPE = "_uscan._tcp";

    /** Wird für jeden gefundenen und aufgelösten Scanner aufgerufen (auf einem Hintergrund-Thread!) */
    public interface Listener {
        void onScannerFound(EsclScanner scanner);
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final Deque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private boolean running = false;

    public ScannerDiscovery(Context context, Listener listener) {
        this.nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
        @Override
        public void onServiceFound(NsdServiceInfo info) {
            Log.i(TAG, "Gefunden: " + info.getServiceName());
            enqueueResolve(info);
        }

        @Override public void onServiceLost(NsdServiceInfo info) {}
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onDiscoveryStopped(String type) {}

        @Override
        public void onStartDiscoveryFailed(String type, int errorCode) {
            Log.w(TAG, "Suche konnte nicht starten: " + errorCode);
            running = false;
        }

        @Override
        public void onStopDiscoveryFailed(String type, int errorCode) {}
    };

    /** Suche im WLAN starten (mehrfacher Aufruf ist harmlos) */
    public synchronized void start() {
        if (running || nsd == null) return;
        running = true;
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    /**
     * Suche beenden und ausstehende Auflösungen verwerfen – spätestens beim Verlassen des Bildschirms aufrufen
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            nsd.stopServiceDiscovery(discoveryListener);
        } catch (IllegalArgumentException ignored) {
            // war schon gestoppt
        }
        resolveQueue.clear();
    }

    private synchronized void enqueueResolve(NsdServiceInfo info) {
        resolveQueue.add(info);
        resolveNext();
    }

    @SuppressWarnings("deprecation")   // resolveService: Ersatz erst ab API 34, wir brauchen ab 23
    private synchronized void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || !running) return;
        resolving = true;
        nsd.resolveService(resolveQueue.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                EsclScanner scanner = toScanner(info);
                if (scanner != null) listener.onScannerFound(scanner);
                done();
            }

            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "Auflösen fehlgeschlagen: " + info.getServiceName() + " (" + errorCode + ")");
                done();
            }

            private void done() {
                synchronized (ScannerDiscovery.this) {
                    resolving = false;
                }
                resolveNext();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static EsclScanner toScanner(NsdServiceInfo info) {
        InetAddress host = null;
        // IPv4 bevorzugen: IPv6-Link-Local-Adressen bräuchten Zonen-Angabe in der URL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            List<InetAddress> all = info.getHostAddresses();
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) {
                    host = a;
                    break;
                }
            }
            if (host == null && !all.isEmpty()) host = all.get(0);
        } else {
            host = info.getHost();
        }
        if (host == null) return null;

        Map<String, byte[]> txt = info.getAttributes();
        String rs = txtValue(txt, "rs", "eSCL");          // Pfad der Schnittstelle, meist "eSCL"
        String name = txtValue(txt, "ty", info.getServiceName());   // Modellname
        if (rs.startsWith("/")) rs = rs.substring(1);

        String address = host instanceof Inet4Address
                ? host.getHostAddress()
                : "[" + host.getHostAddress() + "]";
        String base = "http://" + address + ":" + info.getPort() + "/" + rs;
        Log.i(TAG, "Scanner: " + name + " → " + base);
        return new EsclScanner(name, base);
    }

    private static String txtValue(Map<String, byte[]> txt, String key, String fallback) {
        byte[] value = txt == null ? null : txt.get(key);
        if (value == null || value.length == 0) return fallback;
        return new String(value, StandardCharsets.UTF_8);
    }
}
