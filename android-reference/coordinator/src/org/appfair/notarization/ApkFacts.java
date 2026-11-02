package org.appfair.notarization;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * What the coordinator establishes about a staged install by itself, before asking anyone.
 * For a complete single APK this is the app identity the specification's request carries:
 * package name, version code, APK SHA-256 and signing-certificate SHA-256. Nothing else about
 * the device is collected.
 *
 * Sessions this reference does not support are flagged rather than guessed at: a staging
 * directory with more than one APK (split or multi-package sessions) and streaming
 * (incremental) sessions, whose file may be incomplete and is never hashed.
 */
public final class ApkFacts {
    public final String packageName;
    public final long versionCode;
    public final String installer;
    public final String apkPath;
    public final String apkSha256;
    public final String signerSha256;
    public final String label;
    /** Why the session cannot be assessed, or null. */
    public final String unsupported;

    private ApkFacts(String packageName, long versionCode, String installer, String apkPath,
                     String apkSha256, String signerSha256, String label, String unsupported) {
        this.packageName = packageName; this.versionCode = versionCode; this.installer = installer;
        this.apkPath = apkPath; this.apkSha256 = apkSha256; this.signerSha256 = signerSha256;
        this.label = label; this.unsupported = unsupported;
    }

    /** The facts of a complete, staged session: reads and hashes the APK. */
    public static ApkFacts gather(PackageManager pm, String packageName, long versionCode,
                                  String installer, Uri stage) {
        String apkPath = apkPath(stage);
        String unsupported = null;
        File dir = stage == null || stage.getPath() == null ? null : new File(stage.getPath());
        if (dir != null && dir.isDirectory()) {
            File[] apks = dir.listFiles((d, name) -> name.endsWith(".apk"));
            if (apks != null && apks.length > 1) {
                unsupported = "This install session contains " + apks.length + " APKs (a split or multi-package "
                        + "session). The reference coordinator assesses single-APK sessions only.";
            }
        }
        String label = packageName;
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apkPath, 0);
            if (info != null) {
                if (packageName == null) packageName = info.packageName;
                if (versionCode < 0) versionCode = info.getLongVersionCode();
                ApplicationInfo ai = info.applicationInfo;
                ai.sourceDir = apkPath;
                ai.publicSourceDir = apkPath;
                label = String.valueOf(ai.loadLabel(pm));
            }
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not read archive info of " + apkPath + ": " + e);
        }
        return new ApkFacts(packageName, versionCode, installer, apkPath,
                sha256OfFile(apkPath), signingCertDigest(pm, apkPath), label, unsupported);
    }

    /** The facts of a streaming session: what the system said, without touching the file. */
    public static ApkFacts streaming(String packageName, long versionCode, String installer, Uri stage) {
        return new ApkFacts(packageName, versionCode, installer, apkPath(stage), null, null,
                packageName == null ? "the app" : packageName,
                "This is a streaming (incremental) install. Its file may be incomplete, so the exact APK cannot "
                        + "be authenticated and cached assessments cannot be applied to it.");
    }

    /** The staged APK: the system hands us the staging directory; the base APK sits inside it. */
    public static String apkPath(Uri uri) {
        if (uri == null || uri.getPath() == null) return null;
        File f = new File(uri.getPath());
        if (f.isDirectory()) {
            File base = new File(f, "base.apk");
            if (base.exists()) return base.getPath();
            File[] apks = f.listFiles((dir, name) -> name.endsWith(".apk"));
            if (apks != null && apks.length > 0) return apks[0].getPath();
            return base.getPath();
        }
        return f.getPath();
    }

    public static Drawable loadIcon(PackageManager pm, String apkPath) {
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apkPath, 0);
            ApplicationInfo ai = info.applicationInfo;
            ai.sourceDir = apkPath;
            ai.publicSourceDir = apkPath;
            return ai.loadIcon(pm);
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256OfFile(String path) {
        if (path == null) return null;
        try (InputStream in = new FileInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not hash " + path + ": " + e);
            return null;
        }
    }

    /** SHA-256 of the DER-encoded signing certificate: the identity developer registries key on. */
    static String signingCertDigest(PackageManager pm, String apkPath) {
        if (apkPath == null) return null;
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES);
            return signerDigest(info.signingInfo);
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not read signing certificate of " + apkPath + ": " + e);
            return null;
        }
    }

    /** The current signer: the newest certificate of a rotated lineage, else the only one. */
    static String signerDigest(SigningInfo si) throws Exception {
        Signature[] sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        Signature current = si.hasMultipleSigners() ? sigs[0] : sigs[sigs.length - 1];
        return hex(MessageDigest.getInstance("SHA-256").digest(current.toByteArray()));
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String abbreviate(String digest) {
        if (digest == null) return "unknown";
        return digest.length() > 16 ? digest.substring(0, 16) + "…" : digest;
    }
}
