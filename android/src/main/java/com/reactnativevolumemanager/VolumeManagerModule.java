package com.reactnativevolumemanager;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import android.view.Window;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.ActionMode;
import android.view.SearchEvent;
import android.view.KeyboardShortcutGroup;
import android.annotation.TargetApi;
import android.content.ContextWrapper;

@ReactModule(name = VolumeManagerModule.NAME)
public class VolumeManagerModule
  extends ReactContextBaseJavaModule
  implements ActivityEventListener, LifecycleEventListener {

  public static final String NAME = "VolumeManager";
  public static volatile boolean suppressVolumeUI = false;
  public static volatile VolumeManagerModule instance = null;
  private final String TAG = VolumeManagerModule.class.getSimpleName();

  private static final String VOL_VOICE_CALL = "call";
  private static final String VOL_SYSTEM = "system";
  private static final String VOL_RING = "ring";
  private static final String VOL_MUSIC = "music";
  private static final String VOL_ALARM = "alarm";
  private static final String VOL_NOTIFICATION = "notification";

  private final ReactApplicationContext mContext;
  private final AudioManager am;
  private final VolumeBroadcastReceiver volumeBR;

  private Boolean showNativeVolumeUI = true;
  private Boolean useDeviceEmitter = false;
  private Boolean hardwareButtonListenerRegistered = false;
  private int volumeUpPressCount = 0;
  private int volumeDownPressCount = 0;
  private long lastVolumeUpPressTime = 0;
  private long lastVolumeDownPressTime = 0;
  private final long doubleClickThreshold = 350; // Milliseconds for double/triple detection
  private final long holdThreshold = 400; // Milliseconds for hold detection
  private boolean isVolumeUpHeld = false;
  private boolean isVolumeDownHeld = false;
  private Handler handler = new Handler(Looper.getMainLooper());
  private Runnable volumeUpHoldRunnable = null;
  private Runnable volumeDownHoldRunnable = null;
  private Runnable volumeUpTapRunnable = null;
  private Runnable volumeDownTapRunnable = null;

  String category;

  public VolumeManagerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mContext = reactContext;
    reactContext.addLifecycleEventListener(this);
    am =
      (AudioManager) mContext
        .getApplicationContext()
        .getSystemService(Context.AUDIO_SERVICE);
    volumeBR = new VolumeBroadcastReceiver();
    this.category = null;
    suppressVolumeUI = !showNativeVolumeUI;
    instance = this;
  }

  private void registerVolumeReceiver() {
    if (!volumeBR.isRegistered()) {
      IntentFilter filter = new IntentFilter(
        "android.media.VOLUME_CHANGED_ACTION"
      );
      mContext.registerReceiver(volumeBR, filter);
      volumeBR.setRegistered(true);
    }
  }

  private void unregisterVolumeReceiver() {
    if (volumeBR.isRegistered()) {
      try {
        mContext.unregisterReceiver(volumeBR);
        volumeBR.setRegistered(false);
      } catch (IllegalArgumentException e) {
        Log.e(TAG, "Error unregistering volume receiver", e);
      }
    }
  }

  private void setupKeyListener() {
    syncWindowCallbacks(mContext.getCurrentActivity());
  }

  

  @ReactMethod
  public void showNativeVolumeUI(ReadableMap config) {
    showNativeVolumeUI = config.getBoolean("enabled");
    useDeviceEmitter = config.getBoolean("deviceEmitter");
    suppressVolumeUI = !showNativeVolumeUI;
    // we want to listen to the hardware volume key buttons
    if (useDeviceEmitter) {
      setupCustomKeyListener();
    } else {
      if (showNativeVolumeUI) {
        cleanupKeyListener();
      } else {
        setupKeyListener();
      }
    }
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void enable(final Boolean enabled) {
    // no op
  }

  @ReactMethod
  public void setCategory(final String category, final Boolean mixWithOthers) {
    // no op
  }

  @ReactMethod
  public void getRingerMode(Promise promise) {
    int mode = am.getRingerMode();
    promise.resolve(mode);
  }

  private boolean hasDndAccess() {
    NotificationManager nm = (NotificationManager) mContext.getSystemService(
      Context.NOTIFICATION_SERVICE
    );
    return (
      (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) ||
      nm.isNotificationPolicyAccessGranted()
    );
  }

  @ReactMethod
  public void checkDndAccess(Promise promise) {
    promise.resolve(hasDndAccess());
  }

  @ReactMethod
  public void requestDndAccess(Promise promise) {
    if (!hasDndAccess() && mContext.hasCurrentActivity()) {
      Intent intent = null;
      if (
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
      ) {
        intent =
          new Intent(
            android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
          );
      }
      assert intent != null;
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

      Context context = mContext.getCurrentActivity().getApplicationContext();
      context.startActivity(intent);
      promise.resolve(true);
    }

    promise.resolve(false);
  }

  @ReactMethod
  public void setRingerMode(int mode, Promise promise) {
    try {
      am.setRingerMode(mode);

      promise.resolve(mode);
    } catch (Exception err) {
      promise.reject(err);
    }
  }

  @ReactMethod
  public void setVolume(float val, ReadableMap config) {
    unregisterVolumeReceiver();
    String type = config.getString("type");
    boolean playSound = config.getBoolean("playSound");
    boolean showUI = config.getBoolean("showUI");
    assert type != null;
    int volType = getVolType(type);
    int flags = 0;
    if (playSound) {
      flags |= AudioManager.FLAG_PLAY_SOUND;
    }
    if (showUI) {
      flags |= AudioManager.FLAG_SHOW_UI;
    }
    try {
      am.setStreamVolume(
        volType,
        (int) (val * am.getStreamMaxVolume(volType)),
        flags
      );
    } catch (SecurityException e) {
      if (val == 0) {
        Log.w(
          TAG,
          "setVolume(0) failed. See https://github.com/c19354837/react-native-system-setting/issues/48"
        );
        NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(
          Context.NOTIFICATION_SERVICE
        );
        if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
          !notificationManager.isNotificationPolicyAccessGranted()
        ) {
          Intent intent = new Intent(
            android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
          );
          mContext.startActivity(intent);
        }
      }
      Log.e(TAG, "err", e);
    }
    registerVolumeReceiver();
  }

  @ReactMethod
  public void getVolume(Promise promise) {
    WritableMap result = Arguments.createMap();
    result.putDouble("volume", getNormalizationVolume(VOL_MUSIC));
    result.putDouble(VOL_VOICE_CALL, getNormalizationVolume(VOL_VOICE_CALL));
    result.putDouble(VOL_SYSTEM, getNormalizationVolume(VOL_SYSTEM));
    result.putDouble(VOL_RING, getNormalizationVolume(VOL_RING));
    result.putDouble(VOL_MUSIC, getNormalizationVolume(VOL_MUSIC));
    result.putDouble(VOL_ALARM, getNormalizationVolume(VOL_ALARM));
    result.putDouble(
      VOL_NOTIFICATION,
      getNormalizationVolume(VOL_NOTIFICATION)
    );
    promise.resolve(result);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @ReactMethod
  public void removeListeners(int count) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  private float getNormalizationVolume(String type) {
    int volType = getVolType(type);
    return am.getStreamVolume(volType) * 1.0f / am.getStreamMaxVolume(volType);
  }

  private int getVolType(String type) {
    switch (type) {
      case VOL_VOICE_CALL:
        return AudioManager.STREAM_VOICE_CALL;
      case VOL_SYSTEM:
        return AudioManager.STREAM_SYSTEM;
      case VOL_RING:
        return AudioManager.STREAM_RING;
      case VOL_ALARM:
        return AudioManager.STREAM_ALARM;
      case VOL_NOTIFICATION:
        return AudioManager.STREAM_NOTIFICATION;
      default:
        return AudioManager.STREAM_MUSIC;
    }
  }

  private void cleanupKeyListener() {
    syncWindowCallbacks(mContext.getCurrentActivity());
  }

  @Override
  public void onActivityResult(
    Activity activity,
    int requestCode,
    int resultCode,
    Intent data
  ) {}

  @Override
  public void onNewIntent(Intent intent) {
    //
  }

  @Override
  public void onHostResume() {
    Log.d(TAG, "Host resumed - registering volume receiver and restoring key listener state: ");
    if (useDeviceEmitter) {
      setupCustomKeyListener();
    } else if (!showNativeVolumeUI) {
      setupKeyListener();
    } else {
      cleanupKeyListener();
    }
    registerVolumeReceiver();
  }

  @Override
  public void onHostPause() {
    unregisterVolumeReceiver();
    cleanupKeyListener(); // Release the key listener when the screen loses focus
  }

  @Override
  public void onHostDestroy() {
    cleanupKeyListener(); // Release the key listener when the root view is destroyed
    instance = null;
  }

  private class VolumeBroadcastReceiver extends BroadcastReceiver {

    private boolean isRegistered = false;

    public void setRegistered(boolean registered) {
      isRegistered = registered;
    }

    public boolean isRegistered() {
      return isRegistered;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.getAction().equals("android.media.VOLUME_CHANGED_ACTION")) {
        WritableMap para = Arguments.createMap();
        para.putDouble("volume", getNormalizationVolume(VOL_MUSIC));
        para.putDouble(VOL_VOICE_CALL, getNormalizationVolume(VOL_VOICE_CALL));
        para.putDouble(VOL_SYSTEM, getNormalizationVolume(VOL_SYSTEM));
        para.putDouble(VOL_RING, getNormalizationVolume(VOL_RING));
        para.putDouble(VOL_MUSIC, getNormalizationVolume(VOL_MUSIC));
        para.putDouble(VOL_ALARM, getNormalizationVolume(VOL_ALARM));
        para.putDouble(
          VOL_NOTIFICATION,
            getNormalizationVolume(VOL_NOTIFICATION));
        try {
          mContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("RNVMEventVolume", para);
        } catch (RuntimeException e) {
          // Possible to interact with volume before JS bundle execution is finished.
          // This is here to avoid app crashing.
        }
      }
    }
  }
  private void setupCustomKeyListener() {
    syncWindowCallbacks(mContext.getCurrentActivity());
  }

  private boolean handleCustomKeyEvent(int keyCode, KeyEvent event) {
      long currentTime = System.currentTimeMillis();
      int flags = showNativeVolumeUI ? AudioManager.FLAG_SHOW_UI : 0;
      
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
          if (event.getRepeatCount() == 0) {
              switch (keyCode) {
                  case KeyEvent.KEYCODE_VOLUME_UP:
                      am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        flags
                      );
                      
                      if (!isVolumeUpHeld) {
                          if (currentTime - lastVolumeUpPressTime < doubleClickThreshold) {
                              volumeUpPressCount++;
                          } else {
                              volumeUpPressCount = 1;
                          }
                          lastVolumeUpPressTime = currentTime;
                          isVolumeUpHeld = true;
                          
                          if (volumeUpHoldRunnable != null) {
                              handler.removeCallbacks(volumeUpHoldRunnable);
                          }
                          volumeUpHoldRunnable = new Runnable() {
                              @Override
                              public void run() {
                                  if (isVolumeUpHeld) {
                                      sendEventToJS("volumeUpHold");
                                      isVolumeUpHeld = false;
                                      volumeUpPressCount = 0;
                                  }
                                  volumeUpHoldRunnable = null;
                              }
                          };
                          handler.postDelayed(volumeUpHoldRunnable, holdThreshold);
                      }
                      break;
                      
                  case KeyEvent.KEYCODE_VOLUME_DOWN:
                      am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        flags
                      );
                      
                      if (!isVolumeDownHeld) {
                          if (currentTime - lastVolumeDownPressTime < doubleClickThreshold) {
                              volumeDownPressCount++;
                          } else {
                              volumeDownPressCount = 1;
                          }
                          lastVolumeDownPressTime = currentTime;
                          isVolumeDownHeld = true;
                          
                          if (volumeDownHoldRunnable != null) {
                              handler.removeCallbacks(volumeDownHoldRunnable);
                          }
                          volumeDownHoldRunnable = new Runnable() {
                              @Override
                              public void run() {
                                  if (isVolumeDownHeld) {
                                      sendEventToJS("volumeDownHold");
                                      isVolumeDownHeld = false;
                                      volumeDownPressCount = 0;
                                  }
                                  volumeDownHoldRunnable = null;
                              }
                          };
                          handler.postDelayed(volumeDownHoldRunnable, holdThreshold);
                      }
                      break;
              }
          }
          return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
      } else if (event.getAction() == KeyEvent.ACTION_UP) {
          switch (keyCode) {
              case KeyEvent.KEYCODE_VOLUME_UP:
                  if (isVolumeUpHeld) {
                      isVolumeUpHeld = false;
                      if (volumeUpHoldRunnable != null) {
                          handler.removeCallbacks(volumeUpHoldRunnable);
                          volumeUpHoldRunnable = null;
                      }
                  }
                  if (volumeUpTapRunnable != null) {
                      handler.removeCallbacks(volumeUpTapRunnable);
                  }
                  volumeUpTapRunnable = new Runnable() {
                      @Override
                      public void run() {
                          switch (volumeUpPressCount) {
                              case 1:
                                  sendEventToJS("volumeUpSingle");
                                  break;
                              case 2:
                                  sendEventToJS("volumeUpDouble");
                                  break;
                              case 3:
                                  sendEventToJS("volumeUpTriple");
                                  break;
                          }
                          volumeUpPressCount = 0;
                          volumeUpTapRunnable = null;
                      }
                  };
                  handler.postDelayed(volumeUpTapRunnable, doubleClickThreshold);
                  return true;
                  
              case KeyEvent.KEYCODE_VOLUME_DOWN:
                  if (isVolumeDownHeld) {
                      isVolumeDownHeld = false;
                      if (volumeDownHoldRunnable != null) {
                          handler.removeCallbacks(volumeDownHoldRunnable);
                          volumeDownHoldRunnable = null;
                      }
                  }
                  if (volumeDownTapRunnable != null) {
                      handler.removeCallbacks(volumeDownTapRunnable);
                  }
                  volumeDownTapRunnable = new Runnable() {
                      @Override
                      public void run() {
                          switch (volumeDownPressCount) {
                              case 1:
                                  sendEventToJS("volumeDownSingle");
                                  break;
                              case 2:
                                  sendEventToJS("volumeDownDouble");
                                  break;
                              case 3:
                                  sendEventToJS("volumeDownTriple");
                                  break;
                          }
                          volumeDownPressCount = 0;
                          volumeDownTapRunnable = null;
                      }
                  };
                  handler.postDelayed(volumeDownTapRunnable, doubleClickThreshold);
                  return true;
          }
      }
      return false;
  }

  private Window getWindowFromRootView(View view) {
      try {
          java.lang.reflect.Field windowField = null;
          Class<?> currentClass = view.getClass();
          while (currentClass != null) {
              try {
                  windowField = currentClass.getDeclaredField("mWindow");
                  break;
              } catch (NoSuchFieldException e) {
                  currentClass = currentClass.getSuperclass();
              }
          }
          if (windowField != null) {
              windowField.setAccessible(true);
              return (Window) windowField.get(view);
          }
      } catch (Exception e) {
          Log.e(TAG, "Error getting window from view reflection", e);
      }
      
      // Fallback
      Context context = view.getContext();
      while (context instanceof ContextWrapper) {
          if (context instanceof Activity) {
              return ((Activity) context).getWindow();
          }
          context = ((ContextWrapper) context).getBaseContext();
      }
      return null;
  }

  public void syncWindowCallbacks(Activity activity) {
      if (activity == null) return;
      runOnUiThread(() -> {
          try {
              Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
              Object wmgInstance = wmgClass.getMethod("getInstance").invoke(null);
              java.lang.reflect.Field mViewsField = wmgClass.getDeclaredField("mViews");
              mViewsField.setAccessible(true);
              java.util.List<View> views = (java.util.List<View>) mViewsField.get(wmgInstance);
              
              if (views != null) {
                  for (View view : views) {
                      Window window = getWindowFromRootView(view);
                      if (window != null) {
                          Window.Callback currentCallback = window.getCallback();
                          if (currentCallback != null && !(currentCallback instanceof WindowCallbackWrapper)) {
                              window.setCallback(new WindowCallbackWrapper(currentCallback, this));
                          }
                      }
                  }
              }
          } catch (Exception e) {
              Log.e(TAG, "Error syncing window callbacks", e);
          }
      });
  }

  public boolean handleKeyEvent(int keyCode, KeyEvent event) {
      if (!suppressVolumeUI) {
          return false;
      }
      if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
          if (useDeviceEmitter) {
              return handleCustomKeyEvent(keyCode, event);
          } else {
              if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                  int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                      ? AudioManager.ADJUST_RAISE
                      : AudioManager.ADJUST_LOWER;
                  am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
              }
              return true; // Consumed to hide native UI!
          }
      }
      return false;
  }

  public static void syncCallbacks(Activity activity) {
      VolumeManagerModule activeInstance = instance;
      if (activeInstance != null) {
          activeInstance.syncWindowCallbacks(activity);
      }
  }

  private static class WindowCallbackWrapper implements Window.Callback {
      private final Window.Callback mWrapped;
      private final VolumeManagerModule mModule;

      public WindowCallbackWrapper(Window.Callback wrapped, VolumeManagerModule module) {
          mWrapped = wrapped;
          mModule = module;
      }

      @Override
      public boolean dispatchKeyEvent(KeyEvent event) {
          if (mModule.handleKeyEvent(event.getKeyCode(), event)) {
              return true; // Consumed!
          }
          return mWrapped.dispatchKeyEvent(event);
      }

      @Override
      public boolean dispatchKeyShortcutEvent(KeyEvent event) {
          return mWrapped.dispatchKeyShortcutEvent(event);
      }

      @Override
      public boolean dispatchTouchEvent(MotionEvent event) {
          return mWrapped.dispatchTouchEvent(event);
      }

      @Override
      public boolean dispatchTrackballEvent(MotionEvent event) {
          return mWrapped.dispatchTrackballEvent(event);
      }

      @Override
      public boolean dispatchGenericMotionEvent(MotionEvent event) {
          return mWrapped.dispatchGenericMotionEvent(event);
      }

      @Override
      public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
          return mWrapped.dispatchPopulateAccessibilityEvent(event);
      }

      @Override
      public View onCreatePanelView(int featureId) {
          return mWrapped.onCreatePanelView(featureId);
      }

      @Override
      public boolean onCreatePanelMenu(int featureId, Menu menu) {
          return mWrapped.onCreatePanelMenu(featureId, menu);
      }

      @Override
      public boolean onPreparePanel(int featureId, View view, Menu menu) {
          return mWrapped.onPreparePanel(featureId, view, menu);
      }

      @Override
      public boolean onMenuOpened(int featureId, Menu menu) {
          return mWrapped.onMenuOpened(featureId, menu);
      }

      @Override
      public boolean onMenuItemSelected(int featureId, MenuItem item) {
          return mWrapped.onMenuItemSelected(featureId, item);
      }

      @Override
      public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
          mWrapped.onWindowAttributesChanged(attrs);
      }

      @Override
      public void onContentChanged() {
          mWrapped.onContentChanged();
      }

      @Override
      public void onWindowFocusChanged(boolean hasFocus) {
          mWrapped.onWindowFocusChanged(hasFocus);
      }

      @Override
      public void onAttachedToWindow() {
          mWrapped.onAttachedToWindow();
      }

      @Override
      public void onDetachedFromWindow() {
          mWrapped.onDetachedFromWindow();
      }

      @Override
      public void onPanelClosed(int featureId, Menu menu) {
          mWrapped.onPanelClosed(featureId, menu);
      }

      @Override
      public boolean onSearchRequested() {
          return mWrapped.onSearchRequested();
      }

      @TargetApi(Build.VERSION_CODES.M)
      @Override
      public boolean onSearchRequested(SearchEvent searchEvent) {
          return mWrapped.onSearchRequested(searchEvent);
      }

      @Override
      public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
          return mWrapped.onWindowStartingActionMode(callback);
      }

      @TargetApi(Build.VERSION_CODES.M)
      @Override
      public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
          return mWrapped.onWindowStartingActionMode(callback, type);
      }

      @Override
      public void onActionModeStarted(ActionMode mode) {
          mWrapped.onActionModeStarted(mode);
      }

      @Override
      public void onActionModeFinished(ActionMode mode) {
          mWrapped.onActionModeFinished(mode);
      }

      @TargetApi(Build.VERSION_CODES.N)
      @Override
      public void onProvideKeyboardShortcuts(java.util.List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
          mWrapped.onProvideKeyboardShortcuts(data, menu, deviceId);
      }

      @TargetApi(Build.VERSION_CODES.O)
      @Override
      public void onPointerCaptureChanged(boolean hasCapture) {
          mWrapped.onPointerCaptureChanged(hasCapture);
      }
  }
  private void sendEventToJS(String eventName) {
      if (mContext.hasActiveCatalystInstance()) {
          mContext
              .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
              .emit("VolumeKeyEvent", eventName);
      }
  }
}
