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
 * Font Zoom v11 — AWT Toolkit-level event interception.
 *
 * Instead of per-component listeners (which never fire in Burp 2026),
 * we use Toolkit.addAWTEventListener which receives ALL events at the
 * dispatch level BEFORE they reach any Swing component.
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
    private int eventCount = 0;

    public FontZoomExtender() {
        try {
            zoomLevel = Preferences.userNodeForPackage(getClass()).getInt(PREF_KEY, DEFAULT_ZOOM);
        } catch (Exception ignored) {}
    }

    @Override public void initialize(MontoyaApi mapi) {
        this.api = mapi;
        log("=== Font Zoom v11 initializing ===");

        // Install AWT Toolkit-level listener IMMEDIATELY
        // This catches ALL MouseWheelEvents before any Swing component sees them
        Toolkit.getDefaultToolkit().addAWTEventListener(
            new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent event) {
                    if (!(event instanceof MouseWheelEvent)) return;
                    MouseWheelEvent e = (MouseWheelEvent) event;
                    onWheelEvent(e);
                }
            },
            java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK
        );
        log("installed AWT MOUSE_WHEEL listener on Toolkit");

        // Start thread to find window + install click tracking
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Window frame;
                int attempts = 0;
                while ((frame = api.userInterface().swingUtils().suiteFrame()) == null) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                    if (++attempts % 6 == 0) log("waiting for suiteFrame... (" + attempts + ")");
                }
                log("Found suiteFrame: " + className(frame));
                final Window win = frame;

                // Install click + move listeners on window for target tracking
                win.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        lastClickedJC = deepComponentAt(e.getX(), e.getY(), win);
                        log("CLICK target=" + className(lastClickedJC));
                    }
                    @Override public void mouseEntered(MouseEvent e) {
                        lastHoveredJC = deepComponentAt(e.getX(), e.getY(), win);
                    }
                    @Override public void mouseMoved(MouseEvent e) {
                        lastHoveredJC = deepComponentAt(e.getX(), e.getY(), win);
                    }
                });
                log("installed mouse listeners on " + className(win));

                log("=== Font Zoom v11 READY ===");
            }
        }, "FontZoom-Init");
        t.setDaemon(true);
        t.start();
    }

    /** Handle wheel event at Toolkit level (fires BEFORE Swing dispatch). */
    private void onWheelEvent(MouseWheelEvent e) {
        int cid = ++eventCount;
        int mods = e.getModifiersEx();
        boolean ctrl = (mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        int units = e.getUnitsToScroll();
        Component src = e.getComponent();

        logN(cid, "WHEEL src=" + className(src) + " ctrl=" + ctrl + " units=" + units);

        // Non-Ctrl: let it pass through to Swing (don't consume)
        if (!ctrl) {
            logN(cid, "PASS-THROUGH (normal scroll)");
            return;
        }

        float delta = units < 0 ? FONT_STEP : -FONT_STEP;

        // Find target component
        javax.swing.JComponent target = resolveTarget();
        if (target == null) {
            logN(cid, "SKIP: no target (click a panel first)");
            return;
        }

        // Consume Ctrl+Scroll so Burp doesn't also handle it
        e.consume();
        logN(cid, "CONSUMED ctrl+scroll, zooming " + className(target));

        doZoom(target, delta, cid);
    }

    /** Resolve target: focus > clicked > hovered */
    private javax.swing.JComponent resolveTarget() {
        // Priority 1: Keyboard focus manager's focus owner
        try {
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner instanceof javax.swing.JComponent) {
                javax.swing.JComponent jc = (javax.swing.JComponent) focusOwner;
                if (!isExcluded(jc)) {
                    return jc;
                }
            }
        } catch (Exception ex) {
            log("failed to get focus: " + ex.getMessage());
        }

        // Priority 2: Last clicked
        if (lastClickedJC != null && !isExcluded(lastClickedJC)) {
            return lastClickedJC;
        }

        // Priority 3: Last hovered
        if (lastHoveredJC != null && !isExcluded(lastHoveredJC)) {
            return lastHoveredJC;
        }

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
                if (!isExcluded(jc)) {
                    result = jc;
                }
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

    private void doZoom(javax.swing.JComponent jc, float delta, int cid) {
        java.awt.Font font = jc.getFont();
        if (font == null) {
            logN(cid, "SKIP: null font");
            return;
        }
        float origSize = font.getSize();
        float newSize = Math.max(FONT_MIN, Math.min(FONT_MAX, origSize + delta));
        if (Math.abs(newSize - origSize) > 0.01f) {
            jc.setFont(font.deriveFont(newSize));
            zoomLevel += (newSize > origSize ? 1 : -1);
            persistZoom();
            logN(cid, "ZOOM " + className(jc) + " " + origSize + "->" + newSize);
        }
    }

    private void persistZoom() {
        try { Preferences.userNodeForPackage(getClass()).putInt(PREF_KEY, zoomLevel); }
        catch (Exception ignored) {}
    }

    private void log(String msg) {
        try { api.logging().logToOutput("[FontZoom] " + msg); } catch (Exception ignore) {}
    }

    private synchronized void logN(int id, String msg) {
        log(id + ": " + msg);
    }

    private String className(Object o) {
        if (o == null) return "null";
        String name = o.getClass().getSimpleName();
        if (name.isEmpty()) name = o.getClass().getName();
        return name + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}
