package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.flutter_overlay_window.R;

import java.util.Timer;
import java.util.TimerTask;

import io.flutter.FlutterInjector;
import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private android.widget.FrameLayout flutterContainer;
    private MethodChannel flutterChannel = null;
    private BasicMessageChannel<Object> overlayMessageChannel = null;
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        if (windowManager != null) {
            try {
                View viewToRemove = flutterContainer != null ? flutterContainer : flutterView;
                if (viewToRemove != null && viewToRemove.isAttachedToWindow()) {
                    windowManager.removeView(viewToRemove);
                    Log.d("OverLay", "Overlay view successfully removed from WindowManager");
                } else {
                    Log.w("OverLay", "Overlay view was not attached to window, skipping removeView");
                }
            } catch (IllegalArgumentException e) {
                Log.e("OverLay", "Error removing view from WindowManager: " + e.getMessage());
            } finally {
                windowManager = null;
                if (flutterView != null) {
                    flutterView.detachFromFlutterEngine();
                    flutterView = null;
                }
                flutterContainer = null;
            }
        }
        NotificationManager notificationManager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        isRunning = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && OverlayConstants.ACTION_STOP_OVERLAY.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        validateDartExecutor();
        mResources = getApplicationContext().getResources();
        isRunning = true;
        Log.d("onStartCommand", "Service started");
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                resizeOverlay(width, height, result);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int realWidth;
        int realHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics wm = windowManager.getCurrentWindowMetrics();
            realWidth = wm.getBounds().width();
            realHeight = wm.getBounds().height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(m);
            realWidth = m.widthPixels;
            realHeight = m.heightPixels;
        }
        int orientation = this.getResources().getConfiguration().orientation;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                (WindowSetup.width == -1999 || WindowSetup.width == -1) ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowSetup.width,
                (WindowSetup.height == -1999 || WindowSetup.height == -1) ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowSetup.height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        if (params.width == WindowManager.LayoutParams.MATCH_PARENT)
            params.width = realWidth;
        if (params.height == WindowManager.LayoutParams.MATCH_PARENT)
            params.height = realHeight;

        params.x = 0;
        params.y = 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        params.gravity = WindowSetup.gravity;
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

        flutterView.setFitsSystemWindows(false);

        flutterContainer.setOnTouchListener(this);

        // Wrap FlutterView in a FrameLayout container.
        // This prevents the AccessibilityBridge crash: when the WindowManager
        // detaches the overlay, the FlutterView's getParent() returns the
        // FrameLayout (not null), so AccessibilityBridge.sendAccessibilityEvent()
        // won't throw a NullPointerException.
        flutterContainer = new android.widget.FrameLayout(getApplicationContext());
        flutterContainer.addView(flutterView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Post to handler to ensure FlutterView is fully initialized before adding to WindowManager
        // This prevents "InputChannel is not initialized" crash
        new Handler().post(() -> {
            try {
                if (windowManager != null && flutterContainer != null) {
                    windowManager.addView(flutterContainer, params);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error adding view to WindowManager", e);
            }
        });

        return START_STICKY;
    }

    private int navigationBarHeight() {
        int resourceId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return mResources.getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }

    private View getOverlayView() {
        return flutterContainer != null ? flutterContainer : flutterView;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) getOverlayView().getLayoutParams();
            params.flags = WindowSetup.flag
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1f;
            }
            windowManager.updateViewLayout(getOverlayView(), params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) getOverlayView().getLayoutParams();

            params.width = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
            params.height = (height == -1999 || height == -1) ? WindowManager.LayoutParams.MATCH_PARENT
                    : dpToPx(height);

            int realW, realH;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics wm = windowManager.getCurrentWindowMetrics();
                realW = wm.getBounds().width();
                realH = wm.getBounds().height();
            } else {
                DisplayMetrics m = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(m);
                realW = m.widthPixels;
                realH = m.heightPixels;
            }
            if (params.width == WindowManager.LayoutParams.MATCH_PARENT)
                params.width = realW;
            if (params.height == WindowManager.LayoutParams.MATCH_PARENT)
                params.height = realH;

            windowManager.updateViewLayout(getOverlayView(), params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    @Override
    public void onCreate() {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        Intent serviceIntent = new Intent(this, flutter.overlay.window.flutter_overlay_window.OverlayService.class);
        serviceIntent.setAction(OverlayConstants.ACTION_STOP_OVERLAY);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("drawable", "launcher");
        PendingIntent closeOverlayPendingIntent = PendingIntent.getService(this, 0, serviceIntent, pendingFlags);

        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? getDrawableResourceId("drawable", "launcher") : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .addAction(0, WindowSetup.stopServiceActionTitle, closeOverlayPendingIntent)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType,
                getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return Math.round(dp * mResources.getDisplayMetrics().density);
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) getOverlayView().getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    int xx = params.x + (int) dx;
                    int yy = params.y + (int) dy;
                    params.x = xx;
                    params.y = yy;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(getOverlayView(), params);
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (WindowSetup.positionGravity != "none") {
                        if (windowManager == null)
                            return false;
                        windowManager.updateViewLayout(getOverlayView(), params);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) getOverlayView().getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                            : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    return;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null) {
                    windowManager.updateViewLayout(getOverlayView(), params);
                }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }

    public void validateDartExecutor() {
        try {
            flutterChannel = new MethodChannel(
                    FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
                    OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(
                    FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        } catch (Exception e) {
            FlutterEngineGroup enn = new FlutterEngineGroup(getApplicationContext());
            DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain");
            FlutterEngine engine = enn.createAndRunEngine(getApplicationContext(), dEntry);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
            flutterChannel = new MethodChannel(
                    FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
                    OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(
                    FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }
    }
}