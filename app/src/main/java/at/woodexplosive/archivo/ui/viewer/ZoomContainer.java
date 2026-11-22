package at.woodexplosive.archivo.ui.viewer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fängt Gesten ab, bevor die Kinder (HorizontalScrollView → RecyclerView) sie bekommen:
 * <ul>
 *   <li>Pinch-Zoom (zwei Finger) und Doppeltippen</li>
 *   <li>Schräges Schieben: Ist eingezoomt, übernimmt der Container das Ziehen mit einem Finger und
 *       bewegt beide Richtungen gleichzeitig. Sonst würde je Geste nur eine Scroll-Ebene gewinnen
 *       (waagrecht ODER senkrecht).</li>
 * </ul>
 */
public class ZoomContainer extends FrameLayout {

    /** Meldet Zoom-, Doppeltipp- und Schiebe-Gesten an den Viewer. Koordinaten relativ zum Container. */
    public interface ZoomListener {
        void onZoom(float gestureScale, float focusX, float focusY);
        void onZoomEnd(float gestureScale, float focusX, float focusY);
        void onDoubleTap(float x, float y);

        /** Darf gerade frei (auch schräg) geschoben werden? z.B. nur wenn eingezoomt */
        boolean isPanEnabled();
        /** Ein freies Schieben beginnt (der User scrollt selbst) */
        void onPanStart();
        /** Um dx/dy Pixel schieben (Fingerbewegung; Inhalt folgt dem Finger) */
        void onPan(float dx, float dy);
        /** Finger losgelassen: mit Schwung weiter (Pixel pro Sekunde, Fingerrichtung) */
        void onPanFling(float velocityX, float velocityY);
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private ZoomListener listener;
    private float gestureScale = 1f;
    private boolean childCancelled = false;

    // Freies Schieben
    private boolean panning;
    private float downX, downY, lastX, lastY;
    @Nullable private VelocityTracker velocityTracker;

    public ZoomContainer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        gestureScale = 1f;
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        gestureScale *= detector.getScaleFactor();
                        if (listener != null) listener.onZoom(gestureScale, detector.getFocusX(), detector.getFocusY());
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                        if (listener != null) listener.onZoomEnd(gestureScale, detector.getFocusX(), detector.getFocusY());
                    }
                });

        // Doppeltippen erkennen (die Kinder bekommen die Events trotzdem weiter -> Scrollen geht normal)
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (listener != null) listener.onDoubleTap(e.getX(), e.getY());
                return true;
            }
        });
    }

    /** Empfänger der Gesten setzen (ohne Listener werden alle Berührungen normal weitergereicht) */
    public void setZoomListener(ZoomListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            childCancelled = false;
            panning = false;
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            if (velocityTracker != null) velocityTracker.clear();
        }
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        // Zwei Finger: Zoom-Geste, Kinder abbrechen, Schieben beenden
        if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            cancelChildren(event);
            panning = false;
            return true;
        }

        if (!childCancelled && !panning && action == MotionEvent.ACTION_MOVE
                && listener != null && listener.isPanEnabled()) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            // Erst ab der Wisch-Schwelle übernehmen – Antippen geht weiter an die Kinder.
            // Wir sehen das Event VOR den Kindern, gewinnen also gegen deren Scroll-Erkennung.
            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                cancelChildren(event);
                panning = true;
                lastX = event.getX();
                lastY = event.getY();
                listener.onPanStart();
            }
        }

        if (panning) {
            if (action == MotionEvent.ACTION_MOVE) {
                listener.onPan(event.getX() - lastX, event.getY() - lastY);
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_UP) {
                velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                float vx = velocityTracker.getXVelocity();
                float vy = velocityTracker.getYVelocity();
                if (Math.abs(vx) > minFlingVelocity || Math.abs(vy) > minFlingVelocity) {
                    listener.onPanFling(vx, vy);
                }
                panning = false;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                panning = false;
            }
            return true;
        }

        if (childCancelled) return true;
        return super.dispatchTouchEvent(event);
    }

    /** Den Kindern einmalig ACTION_CANCEL schicken – danach bekommen sie diese Geste nicht mehr */
    private void cancelChildren(MotionEvent event) {
        if (childCancelled) return;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        childCancelled = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
}
