package flutter.overlay.window.flutter_overlay_window;

import android.content.Context;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;

/**
 * A FlutterView subclass that prevents the AccessibilityBridge crash in overlay windows.
 *
 * When an overlay's FlutterView is detached from the WindowManager, getParent() returns null.
 * The Flutter engine's AccessibilityBridge then calls:
 *   rootAccessibilityView.getParent().requestSendAccessibilityEvent(...)
 * which throws a NullPointerException. The C++ JNI layer treats this as FATAL and calls abort(),
 * killing the entire process — including the background location service.
 *
 * This class overrides getParent() to return a safe no-op ViewParent when the real parent is null,
 * preventing the NPE from ever occurring.
 */
public class SafeOverlayFlutterView extends FlutterView {

    private static final ViewParent SAFE_PARENT = new SafeViewParent();

    public SafeOverlayFlutterView(Context context, FlutterTextureView textureView) {
        super(context, textureView);
    }

    @Override
    public ViewParent getParent() {
        ViewParent parent = super.getParent();
        return parent != null ? parent : SAFE_PARENT;
    }

    /**
     * A no-op ViewParent that safely handles accessibility events without crashing.
     * All methods return safe defaults — the overlay doesn't need accessibility support.
     */
    private static class SafeViewParent implements ViewParent {
        @Override
        public boolean requestSendAccessibilityEvent(android.view.View child, AccessibilityEvent event) {
            // Swallow the event — prevents the NullPointerException
            return false;
        }

        // Required ViewParent methods — all return safe no-op values
        @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        @Override public boolean requestChildRectangleOnScreen(android.view.View child, android.graphics.Rect rectangle, boolean immediate) { return false; }
        @Override public void requestChildFocus(android.view.View child, android.view.View focused) {}
        @Override public android.view.View focusSearch(android.view.View v, int direction) { return null; }
        @Override public void focusableViewAvailable(android.view.View v) {}
        @Override public boolean showContextMenuForChild(android.view.View originalView) { return false; }
        @Override public android.view.ActionMode startActionModeForChild(android.view.View originalView, android.view.ActionMode.Callback callback) { return null; }
        @Override public android.view.ActionMode startActionModeForChild(android.view.View originalView, android.view.ActionMode.Callback callback, int type) { return null; }
        @Override public void createContextMenu(android.view.ContextMenu menu) {}
        @Override public void childDrawableStateChanged(android.view.View child) {}
        @Override public void requestTransparentRegion(android.view.View child) {}
        @Override public void invalidateChild(android.view.View child, android.graphics.Rect r) {}
        @Override public ViewParent invalidateChildInParent(int[] location, android.graphics.Rect r) { return null; }
        @Override public ViewParent getParent() { return null; }
        @Override public void requestLayout() {}
        @Override public boolean isLayoutRequested() { return false; }
        @Override public void requestFitSystemWindows() {}
        @Override public boolean getChildVisibleRect(android.view.View child, android.graphics.Rect r, android.graphics.Point offset) { return false; }
        @Override public void childHasTransientStateChanged(android.view.View child, boolean hasTransientState) {}
        @Override public void bringChildToFront(android.view.View child) {}
        @Override public void clearChildFocus(android.view.View child) {}
        @Override public boolean canResolveLayoutDirection() { return false; }
        @Override public boolean isLayoutDirectionResolved() { return false; }
        @Override public int getLayoutDirection() { return 0; }
        @Override public boolean canResolveTextDirection() { return false; }
        @Override public boolean isTextDirectionResolved() { return false; }
        @Override public int getTextDirection() { return 0; }
        @Override public boolean canResolveTextAlignment() { return false; }
        @Override public boolean isTextAlignmentResolved() { return false; }
        @Override public int getTextAlignment() { return 0; }
        @Override public boolean onStartNestedScroll(android.view.View child, android.view.View target, int nestedScrollAxes) { return false; }
        @Override public void onNestedScrollAccepted(android.view.View child, android.view.View target, int nestedScrollAxes) {}
        @Override public void onStopNestedScroll(android.view.View target) {}
        @Override public void onNestedScroll(android.view.View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {}
        @Override public void onNestedPreScroll(android.view.View target, int dx, int dy, int[] consumed) {}
        @Override public boolean onNestedFling(android.view.View target, float velocityX, float velocityY, boolean consumed) { return false; }
        @Override public boolean onNestedPreFling(android.view.View target, float velocityX, float velocityY) { return false; }
        @Override public void notifySubtreeAccessibilityStateChanged(android.view.View child, android.view.View source, int changeType) {}
        @Override public boolean onNestedPrePerformAccessibilityAction(android.view.View target, int action, android.os.Bundle args) { return false; }
    }
}
