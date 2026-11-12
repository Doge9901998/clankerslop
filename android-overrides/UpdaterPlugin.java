package dev.tp7.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * In-app updater: downloads the new APK from this project's GitHub releases, checks it really is an update of this app
 * (same package, same signing key, not older), then hands it to Android's package installer.
 * Android always asks the user to confirm an install from an app that is not the system installer, so the last tap is
 * unavoidable. The app closes itself once that confirmation is on screen.
 * Called from the page as Capacitor plugin "Updater": download({url}), progress(), install(), status().
 */
@CapacitorPlugin(name = "Updater")
public class UpdaterPlugin extends Plugin {

    private static final String ACTION = "dev.tp7.player.INSTALL_RESULT";
    private static final String FILE_NAME = "tp7-update.apk";

    private volatile boolean busy = false;
    private volatile long done = 0;
    private volatile long total = 0;
    private volatile String state = "idle"; // idle | pending | success | failed
    private volatile String message = "";
    private BroadcastReceiver receiver;

    @Override
    public void load() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                String text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    state = "pending";
                    @SuppressWarnings("deprecation")
                    Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(confirm);
                        } catch (Throwable e) {
                            state = "failed";
                            message = "Could not open the installer: " + e;
                            return;
                        }
                        // close the app so the update can replace it; the install dialog stays on screen
                        final Activity a = getActivity();
                        if (a != null) {
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    a.finishAndRemoveTask();
                                }
                            }, 900);
                        }
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    state = "success";
                } else {
                    state = "failed";
                    message = (text == null ? "" : text) + " (code " + status + ")";
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(receiver, filter);
        }
    }

    private File apkFile() {
        return new File(getContext().getCacheDir(), FILE_NAME);
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || !url.startsWith("https://github.com/Doge9901998/clankerslop/releases/download/")) {
            call.reject("The update address is not allowed.");
            return;
        }
        if (busy) {
            call.reject("Already downloading.");
            return;
        }
        busy = true;
        done = 0;
        total = 0;
        state = "idle";
        message = "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                File part = new File(getContext().getCacheDir(), FILE_NAME + ".part");
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        throw new Exception("Download failed (HTTP " + code + ").");
                    }
                    total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    OutputStream out = new FileOutputStream(part);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                        }
                    } finally {
                        out.close();
                        in.close();
                    }
                    if (total > 0 && done != total) {
                        throw new Exception("Download was cut off.");
                    }
                    File apk = apkFile();
                    if (apk.exists()) apk.delete();
                    if (!part.renameTo(apk)) {
                        throw new Exception("Could not save the update.");
                    }
                    JSObject r = new JSObject();
                    r.put("bytes", done);
                    call.resolve(r);
                } catch (Throwable e) {
                    part.delete();
                    call.reject(String.valueOf(e.getMessage() == null ? e : e.getMessage()));
                } finally {
                    busy = false;
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    @PluginMethod
    public void progress(PluginCall call) {
        JSObject r = new JSObject();
        r.put("done", done);
        r.put("total", total);
        r.put("busy", busy);
        call.resolve(r);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", state);
        r.put("message", message);
        call.resolve(r);
    }

    /** Null when the downloaded file is a valid update of this app, otherwise a sentence explaining what is wrong. */
    private String problemWith(File apk) {
        try {
            if (Build.VERSION.SDK_INT < 28) return null;
            Context ctx = getContext();
            PackageManager pm = ctx.getPackageManager();
            PackageInfo fresh = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (fresh == null) return "The downloaded file is not a valid app.";
            if (!ctx.getPackageName().equals(fresh.packageName)) return "The downloaded file is a different app.";
            PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (fresh.signingInfo != null && mine.signingInfo != null) {
                Set<Signature> a = new HashSet<Signature>();
                Set<Signature> b = new HashSet<Signature>();
                for (Signature s : fresh.signingInfo.getApkContentsSigners()) a.add(s);
                for (Signature s : mine.signingInfo.getApkContentsSigners()) b.add(s);
                if (!a.equals(b)) {
                    return "This update is signed with a different key than the installed app, so Android refuses it. "
                            + "Uninstall TP-7 once and install the new build by hand (your imported files will be removed); "
                            + "updates will install normally after that.";
                }
            }
            if (fresh.getLongVersionCode() < mine.getLongVersionCode()) return "The downloaded file is older than the installed app.";
        } catch (Throwable e) {
            return null; // could not check: let Android decide
        }
        return null;
    }

    @PluginMethod
    public void install(PluginCall call) {
        try {
            Context ctx = getContext();
            File apk = apkFile();
            if (!apk.exists()) {
                call.reject("The update has not been downloaded.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(s);
                JSObject r = new JSObject();
                r.put("needPermission", true);
                call.resolve(r);
                return;
            }
            String problem = problemWith(apk);
            if (problem != null) {
                call.reject(problem);
                return;
            }
            state = "idle";
            message = "";
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                InputStream in = new FileInputStream(apk);
                OutputStream out = session.openWrite("tp7.apk", 0, apk.length());
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                } finally {
                    out.close();
                    in.close();
                }
                Intent result = new Intent(ACTION);
                result.setPackage(ctx.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(ctx, id, result, flags);
                session.commit(pending.getIntentSender());
            } finally {
                session.close();
            }
            JSObject r = new JSObject();
            r.put("started", true);
            call.resolve(r);
        } catch (Throwable e) {
            call.reject("Install failed: " + e);
        }
    }
}
