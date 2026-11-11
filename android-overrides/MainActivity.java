package dev.tp7.player;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Replaces the generated MainActivity (the GitHub workflow copies this file over it).
 *
 * 1. Survives the WebView being killed. When another app needs memory, Android kills the WebView's renderer
 *    process. Capacitor does not handle that, and Android's rule is then to kill the whole app, which looks like
 *    "the app crashed when I switched back". Here the activity is simply recreated (the page reloads and picks up
 *    the last track and position).
 * 2. Crash report: if the app died unexpectedly (crash, low-memory kill, ANR...), the next start shows why, with a Copy button.
 *    Android records the exit reason itself (even for native crashes and memory kills), so no computer / adb is needed.
 * 3. Asks for the notification permission (Android 13+). Without it the media notification is never shown.
 */
public class MainActivity extends BridgeActivity {

    private static long lastRendererLoss = 0;
    private static final String PREFS = "tp7_diag";
    private static boolean reportShown = false;
    private static boolean loopFinish = false; // finished because the web view keeps dying: that is NOT a normal close

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Did the previous run end with the user closing the app (swipe away / back button)? Then there is nothing to report.
        boolean cleanBefore = false;
        try {
            SharedPreferences dp = getSharedPreferences(PREFS, MODE_PRIVATE);
            cleanBefore = dp.getBoolean("clean", false);
            dp.edit().putBoolean("clean", false).commit();
        } catch (Throwable ignored) { }
        installCrashRecorder();
        showLastExitReport(cleanBefore);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7007);
        }

        if (getBridge() != null) {
            getBridge().addWebViewListener(new WebViewListener() {
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    try {
                        boolean crashed = Build.VERSION.SDK_INT >= 26 && detail.didCrash();
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("renderer", (crashed ? "The web view process crashed" : "The web view process was killed by Android (usually low memory)")
                                        + " at " + new java.util.Date())
                                .putLong("rendererAt", System.currentTimeMillis()).apply();
                    } catch (Throwable ignored) { }
                    long now = SystemClock.elapsedRealtime();
                    boolean crashLoop = now - lastRendererLoss < 4000;
                    lastRendererLoss = now;
                    if (crashLoop) {
                        loopFinish = true;
                        MainActivity.this.finish();
                    } else {
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                MainActivity.this.recreate();
                            }
                        });
                    }
                    return true;
                }
            });
        }
    }

    /** Remembers the stack trace of an uncaught Java exception so the next start can show it. */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final Context app = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String trace = sw.toString();
                    if (trace.length() > 3500) trace = trace.substring(0, 3500);
                    app.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("crash", trace).putLong("crashAt", System.currentTimeMillis()).commit();
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    /** Called when the activity ends. If the user closed the app, remember that, so the next start does not show a crash report. */
    @Override
    public void onDestroy() { // public: BridgeActivity declares it public, and an override can't be less accessible
        try {
            if (isFinishing() && !loopFinish) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("clean", true).commit();
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /** On start, explains an unexpected previous exit (only if it happened in the last 5 minutes, so ordinary background kills stay quiet). */
    private void showLastExitReport(boolean cleanBefore) {
        if (reportShown) return;
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            long now = System.currentTimeMillis();
            final StringBuilder sb = new StringBuilder();

            String crash = sp.getString("crash", null);
            if (crash != null) {
                if (now - sp.getLong("crashAt", 0) < 300000L) sb.append("Java crash:\n").append(crash).append("\n\n");
                sp.edit().remove("crash").apply();
            }
            String renderer = sp.getString("renderer", null);
            if (renderer != null) {
                if (now - sp.getLong("rendererAt", 0) < 300000L) sb.append(renderer).append("\n\n");
                sp.edit().remove("renderer").apply();
            }
            if (Build.VERSION.SDK_INT >= 30) {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> list = am == null ? null : am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
                if (list != null && !list.isEmpty()) {
                    ApplicationExitInfo e = list.get(0);
                    if (e.getTimestamp() > sp.getLong("exitSeen", 0)) {
                        sp.edit().putLong("exitSeen", e.getTimestamp()).apply();
                        int r = e.getReason();
                        // 1 self, 8 permission change, 10 user requested, 11 user stopped, 14 freezer, 15 permission revoked: ordinary
                        boolean normal = cleanBefore || r == 1 || r == 8 || r == 10 || r == 11 || r == 14 || r == 15;
                        if (!normal && now - e.getTimestamp() < 300000L) {
                            sb.append("Android says the last run ended with: ").append(exitName(r))
                              .append("\nDetails: ").append(e.getDescription())
                              .append("\nImportance when it ended: ").append(e.getImportance())
                              .append("\nMemory (PSS KB): ").append(e.getPss())
                              .append("\nAndroid ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
                        }
                    }
                }
            }
            final String report = sb.toString().trim();
            if (report.length() == 0) return;
            reportShown = true;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("TP-7 closed unexpectedly")
                            .setMessage(report)
                            .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    try {
                                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                        cm.setPrimaryClip(ClipData.newPlainText("TP-7 crash report", report));
                                    } catch (Throwable ignored) { }
                                }
                            })
                            .setNegativeButton("Close", null)
                            .show();
                }
            }, 1200);
        } catch (Throwable ignored) { }
    }

    private static String exitName(int r) {
        switch (r) {
            case 1: return "EXIT_SELF (the app quit itself)";
            case 2: return "SIGNALED (killed by a signal)";
            case 3: return "LOW_MEMORY (Android killed it to free RAM)";
            case 4: return "CRASH (Java exception)";
            case 5: return "CRASH_NATIVE (native crash)";
            case 6: return "ANR (app not responding)";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER (killed by the system)";
            case 14: return "FREEZER";
            case 15: return "PERMISSION_REVOKED";
            case 16: return "EXCESSIVE_CPU_USAGE";
            default: return "code " + r;
        }
    }
}
