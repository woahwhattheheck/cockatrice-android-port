package com.wrapper.cockatrice;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Native Android launcher/viewer only. There is no WebView in the startup path. */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder hostLog = new StringBuilder();
    private FrameLayout displayContainer;
    private NativeRfbView displayView;
    private View setup;
    private View viewerTools;
    private TextView status;
    private TextView diagnostics;
    private ProgressBar progress;
    private Button retry;
    private GameService service;
    private GameService.DisplaySession display;
    private boolean bound;
    private boolean firstFrame;
    private int generation;
    private final GameService.Listener listener = snapshot -> runOnUiThread(() -> render(snapshot));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((GameService.LocalBinder) binder).getService();
            hostEvent("Android game service connected");
            service.addListener(listener);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            showError("Android disconnected the game service. Tap Try again.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        displayContainer = findViewById(R.id.webContainer);
        setup = findViewById(R.id.setup);
        viewerTools = findViewById(R.id.viewerTools);
        status = findViewById(R.id.statusText);
        diagnostics = findViewById(R.id.diagnostics);
        progress = findViewById(R.id.progress);
        retry = findViewById(R.id.retry);
        status.setText("Starting native Android host…");
        progress.setIndeterminate(true);
        hostEvent("Native launcher: no Android browser or JavaScript engine is loaded");
        retry.setOnClickListener(view -> { if (display != null) openDisplay(display); else startAndBind(); });
        findViewById(R.id.copyLog).setOnClickListener(view -> copyDiagnostics());
        findViewById(R.id.openDiagnostics).setOnClickListener(view -> showDiagnostics());
        findViewById(R.id.showKeyboard).setOnClickListener(view -> {
            if (displayView != null) {
                displayView.requestFocus();
                getSystemService(InputMethodManager.class).showSoftInput(displayView, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        findViewById(R.id.pasteText).setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            if (displayView != null && clip != null && clip.getItemCount() > 0) {
                displayView.paste(clip.getItemAt(0).coerceToText(this).toString());
                displayView.requestFocus();
            }
        });
        findViewById(R.id.viewerKeys).setOnClickListener(view -> {
            if (displayView == null) return;
            new AlertDialog.Builder(this).setTitle("Native viewer controls")
                    .setItems(new String[]{"Escape", "Tab", "Enter", "Fit screen"}, (dialog, item) -> {
                        if (displayView == null) return;
                        if (item == 3) displayView.fit();
                        else displayView.tapKey(new int[]{0xff1b, 0xff09, 0xff0d}[item]);
                        displayView.requestFocus();
                    }).show();
        });
        setup.postDelayed(() -> { collectPreviousFailure(); startAndBind(); }, 100);
    }

    private void startAndBind() {
        if (isFinishing() || isDestroyed()) return;
        try {
            status.setText("Connecting to Android game service…");
            progress.setIndeterminate(true);
            retry.setVisibility(View.GONE);
            startForegroundService(new Intent(this, GameService.class));
            if (!bound) bound = bindService(new Intent(this, GameService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("Android refused the game-service binding");
            main.postDelayed(() -> { if (service == null && !isFinishing()) showError("Android has not connected the game service. Copy diagnostics."); }, 10000);
        } catch (RuntimeException e) { showError("Android startup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    private void render(GameService.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        if (snapshot.display != null) {
            if (snapshot.display != display) { display = snapshot.display; openDisplay(display); }
            return;
        }
        status.setText(snapshot.message);
        progress.setIndeterminate(snapshot.percent < 0);
        if (snapshot.percent >= 0) progress.setProgress(snapshot.percent);
        diagnostics.setText(snapshot.error ? allDiagnostics() : "Everything is bundled. Cockatrice itself is unchanged.");
        retry.setVisibility(snapshot.error ? View.VISIBLE : View.GONE);
        setup.setVisibility(View.VISIBLE);
        displayContainer.setVisibility(View.GONE);
        viewerTools.setVisibility(View.GONE);
        if (snapshot.error) { display = null; closeDisplay(); }
    }

    private void openDisplay(GameService.DisplaySession session) {
        if (isFinishing() || isDestroyed()) return;
        closeDisplay();
        final int current = ++generation;
        firstFrame = false;
        setup.setVisibility(View.VISIBLE);
        displayContainer.setVisibility(View.VISIBLE);
        viewerTools.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        status.setText("Connecting native game display…");
        diagnostics.setText("The original Cockatrice is running. Connecting directly to its private display; no browser needed.");
        progress.setIndeterminate(true);
        hostEvent("Connecting native framebuffer viewer");
        displayView = new NativeRfbView(this, session.port, session.password, new NativeRfbView.Events() {
            @Override public void firstFrame() {
                if (current != generation || isDestroyed()) return;
                firstFrame = true;
                setup.setVisibility(View.GONE);
                viewerTools.setVisibility(View.VISIBLE);
                displayContainer.setVisibility(View.VISIBLE);
                displayView.requestFocus();
                hostEvent("Original Cockatrice pixels are being drawn by the native viewer");
            }
            @Override public void failure(String message) {
                if (current == generation) showError("Native display: " + message + ". Try again reconnects the viewer only.");
            }
        });
        displayContainer.addView(displayView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        displayView.start();
        main.postDelayed(() -> {
            if (current == generation && !firstFrame && !isFinishing()) showError("No game pixels arrived from the native display. Copy diagnostics.");
        }, 20000);
    }

    private void closeDisplay() {
        generation++;
        if (displayView != null) { displayView.close(); displayContainer.removeView(displayView); displayView = null; }
    }

    private void hostEvent(String message) {
        String safe = message.substring(0, Math.min(message.length(), 12000));
        hostLog.append(safe).append('\n');
        if (hostLog.length() > 20000) hostLog.delete(0, hostLog.length() - 20000);
        if (service != null) service.recordHostEvent(safe);
    }

    private String allDiagnostics() {
        return "Cockatrice native Android host " + BuildConfig.VERSION_NAME + "\n" + hostLog + "\n"
                + (service == null ? "Game service has not connected." : service.diagnostics());
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) return;
        hostEvent(message);
        status.setText(message);
        diagnostics.setText(allDiagnostics());
        progress.setIndeterminate(false);
        progress.setProgress(0);
        retry.setVisibility(View.VISIBLE);
        setup.setVisibility(View.VISIBLE);
        displayContainer.setVisibility(View.GONE);
        viewerTools.setVisibility(View.GONE);
    }

    private void copyDiagnostics() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Cockatrice diagnostics", allDiagnostics()));
        android.widget.Toast.makeText(this, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showDiagnostics() {
        TextView text = new TextView(this);
        text.setText(allDiagnostics()); text.setTextIsSelectable(true); text.setPadding(24, 16, 24, 16);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Android host diagnostics").setView(scroll)
                .setPositiveButton("Copy", (dialog, which) -> copyDiagnostics()).setNegativeButton("Close", null).show();
    }

    private void collectPreviousFailure() {
        if (Build.VERSION.SDK_INT < 30) return;
        Thread reader = new Thread(() -> {
            try {
                ActivityManager manager = getSystemService(ActivityManager.class);
                for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 5)) {
                    int reason = exit.getReason();
                    if (reason != ApplicationExitInfo.REASON_ANR && reason != ApplicationExitInfo.REASON_CRASH && reason != ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
                    StringBuilder detail = new StringBuilder("Previous Android exit: reason=").append(reason).append(" status=").append(exit.getStatus())
                            .append(" time=").append(exit.getTimestamp()).append(" ").append(exit.getDescription());
                    if (reason == ApplicationExitInfo.REASON_ANR) {
                        try (java.io.InputStream trace = exit.getTraceInputStream()) {
                            if (trace != null) { byte[] bytes = new byte[10000]; int count = trace.read(bytes);
                                if (count > 0) detail.append('\n').append(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8)); }
                        }
                    }
                    String message = detail.toString(); main.post(() -> { if (!isDestroyed()) hostEvent(message); });
                }
            } catch (Exception e) { main.post(() -> { if (!isDestroyed()) hostEvent("Previous Android exit details unavailable: " + e.getClass().getSimpleName()); }); }
        }, "cockatrice-previous-exit");
        reader.setDaemon(true); reader.start();
    }

    @Override public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("Leave Cockatrice?").setMessage("Save your decks before stopping.")
                .setPositiveButton("Keep running", (dialog, which) -> moveTaskToBack(true))
                .setNegativeButton("Stop game", (dialog, which) -> { stopService(new Intent(this, GameService.class)); finish(); })
                .setNeutralButton("Diagnostics", (dialog, which) -> showDiagnostics()).show();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (service != null) service.removeListener(listener);
        if (bound) unbindService(connection);
        closeDisplay();
        super.onDestroy();
    }
}
