package exp.fontzoom;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.prefs.Preferences;

/**
 * Font Zoom — AWT Toolkit-level event interception.
 *
 * Ctrl+Scroll: zoom the focused component's font, then consume the event
 *   so Burp doesn't also scroll.
 * Normal scroll: let events flow through to Swing's default handling.
 */
public class FontZoomExtender implements BurpExtension {

    private static final int FONT_STEP = 1;
    private static final int FONT_MIN  = 6;
    private static final int FONT_MAX  = 72;
    private static final String PREF_KEY = "fontZoomLevel";
    private static final int DEFAULT_ZOOM = 0;

    private int zoomLevel = DEFAULT_ZOOM;
    private MontoyaApi api;
    private volatile javax.swing.JComponent lastClickedJC;
    private volatile javax.swing.JComponent lastHoveredJC;

    public FontZoomExtender() {
        try {
            zoomLevel = Preferences.userNodeForPackage(getClass()).getInt(PREF_KEY, DEFAULT_ZOOM);
        } catch (Exception ignored) {}
    }

    @Override public void initialize(MontoyaApi mapi) {
        this.api = mapi;

        // Toolkit-level listener catches ALL MouseWheelEvents before any Swing component
        Toolkit.getDefaultToolkit().addAWTEventListener(
            event -> onWheelEvent((MouseWheelEvent) event),
            AWTEvent.MOUSE_WHEEL_EVENT_MASK
        );

        // Start thread to find window + install click tracking
        Thread t = new Thread(() -> {
            Window frame;
            while ((frame = api.userInterface().swingUtils().suiteFrame()) == null) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
            final Window win = frame;

            win.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    lastClickedJC = deepComponentAt(e.getX(), e.getY(), win);
                }
                @Override public void mouseEntered(MouseEvent e) {
                    lastHoveredJC = deepComponentAt(e.getX(), e.getY(), win);
                }
                @Override public void mouseMoved(MouseEvent e) {
                    lastHoveredJC = deepComponentAt(e.getX(), e.getY(), win);
                }
            });
        }, "FontZoom-Init");
        t.setDaemon(true);
        t.start();
    }

    private void onWheelEvent(MouseWheelEvent e) {
        int mods = e.getModifiersEx();
        boolean ctrl = (mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        if (!ctrl) return; // Normal scroll: pass through

        float delta = e.getUnitsToScroll() < 0 ? FONT_STEP : -FONT_STEP;
        javax.swing.JComponent target = resolveTarget();
        if (target == null) return;

        e.consume(); // Prevent Burp from also handling Ctrl+Scroll
        doZoom(target, delta);
    }

    /** Resolve target: focus > clicked > hovered */
    private javax.swing.JComponent resolveTarget() {
        try {
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner instanceof javax.swing.JComponent) {
                javax.swing.JComponent jc = (javax.swing.JComponent) focusOwner;
                if (!isExcluded(jc)) return jc;
            }
        } catch (Exception ignored) {}

        if (lastClickedJC != null && !isExcluded(lastClickedJC)) return lastClickedJC;
        if (lastHoveredJC != null && !isExcluded(lastHoveredJC)) return lastHoveredJC;

        return null;
    }

    private javax.swing.JComponent deepComponentAt(int x, int y, Container root) {
        javax.swing.JComponent result = null;
        Component cur = root;
        Point p = new Point(x, y);

        while (cur instanceof Container) {
            Component hit = ((Container) cur).getComponentAt(p);
            if (hit == null || hit == cur) break;
            cur = hit;
            if (cur instanceof javax.swing.JComponent) {
                javax.swing.JComponent jc = (javax.swing.JComponent) cur;
                if (!isExcluded(jc)) result = jc;
            }
        }
        return result;
    }

    private boolean isExcluded(javax.swing.JComponent jc) {
        String name = jc.getClass().getSimpleName().toLowerCase();
        return name.contains("scroll") ||
               name.equals("jviewport") ||
               name.equals("jlayeredpane") ||
               name.contains("layered");
    }

    private void doZoom(javax.swing.JComponent jc, float delta) {
        java.awt.Font font = jc.getFont();
        if (font == null) return;
        float origSize = font.getSize();
        float newSize = Math.max(FONT_MIN, Math.min(FONT_MAX, origSize + delta));
        if (Math.abs(newSize - origSize) > 0.01f) {
            jc.setFont(font.deriveFont(newSize));
            zoomLevel += (newSize > origSize ? 1 : -1);
            persistZoom();
        }
    }

    private void persistZoom() {
        try { Preferences.userNodeForPackage(getClass()).putInt(PREF_KEY, zoomLevel); }
        catch (Exception ignored) {}
    }
}
