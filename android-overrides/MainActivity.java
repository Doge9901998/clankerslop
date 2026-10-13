package dev.tp7.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

/**
 * Replaces the generated MainActivity (the GitHub workflow copies this file over it).
 *
 * 1. Survives the WebView being killed. When another app needs memory, Android kills the WebView's renderer
 *    process. Capacitor does not handle that, and Android's rule is then to kill the whole app, which looks like
 *    "the app crashed when I switched back". Here the activity is simply recreated (the page reloads and picks up
 *    the last track and position).
 * 2. Asks for the notification permission (Android 13+). Without it the media notification is never shown.
 */
public class MainActivity extends BridgeActivity {

    private static long lastRendererLoss = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7007);
        }

        if (getBridge() != null) {
            getBridge().addWebViewListener(new WebViewListener() {
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    long now = SystemClock.elapsedRealtime();
                    boolean crashLoop = now - lastRendererLoss < 4000;
                    lastRendererLoss = now;
                    if (crashLoop) {
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
}
