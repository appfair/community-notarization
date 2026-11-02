package org.appfair.notarization;

import android.content.Context;
import android.util.Log;

import org.appfair.notarization.provider.NotaryContract;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Cached assessments (specification, "Reuse evidence"). A valid answer is kept until its
 * signed expiry, at most 24 hours, and reused only when a live request for the same provider,
 * the same configuration and the same exact APK fails: timeout, crash or no connection. Reuse
 * never extends the expiry. An invalid response removes the entry and prevents the fallback.
 * Changing the trust configuration leaves old entries unreachable because the configuration
 * is part of the key; {@link SettingsActivity} also clears the file outright.
 */
public final class EvidenceStore {
    private final File file;

    public EvidenceStore(Context context) {
        file = new File(context.createDeviceProtectedStorageContext().getFilesDir(), "evidence.json");
    }

    static String key(String configuration, ProviderRegistry.Provider provider, ApkFacts facts) {
        return provider.id + "|apk:" + facts.apkSha256 + "|" + configuration;
    }

    /** The unexpired cached assessment, or null. */
    public synchronized Assessment find(String configuration, ProviderRegistry.Provider provider, ApkFacts facts) {
        if (facts.apkSha256 == null) return null;
        JSONObject o = read().optJSONObject(key(configuration, provider, facts));
        if (o == null || o.optLong("expires") <= System.currentTimeMillis()) return null;
        JSONObject a = o.optJSONObject("assessment");
        return a == null ? null : Assessment.fromJson(a);
    }

    public synchronized void save(String configuration, ProviderRegistry.Provider provider, ApkFacts facts,
                                  Assessment assessment, long expiresAt) {
        if (!assessment.verdict.answered() || assessment.source == Assessment.Source.CACHE || facts.apkSha256 == null) return;
        long expires = Math.min(expiresAt, System.currentTimeMillis() + NotaryContract.EVIDENCE_LIFETIME_MS);
        try {
            JSONObject all = read();
            all.put(key(configuration, provider, facts), new JSONObject()
                    .put("expires", expires).put("assessment", assessment.toJson()));
            write(all);
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not save evidence: " + e);
        }
    }

    public synchronized void invalidate(String configuration, ProviderRegistry.Provider provider, ApkFacts facts) {
        JSONObject all = read();
        if (all.remove(key(configuration, provider, facts)) != null) write(all);
    }

    public synchronized int count() {
        JSONObject all = read();
        int n = 0;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject o = all.optJSONObject(it.next());
            if (o != null && o.optLong("expires") > System.currentTimeMillis()) n++;
        }
        return n;
    }

    public synchronized void clear() {
        file.delete();
    }

    private JSONObject read() {
        try {
            if (file.exists()) return new JSONObject(new String(Files.readAllBytes(file.toPath()), "UTF-8"));
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not read evidence: " + e);
        }
        return new JSONObject();
    }

    private void write(JSONObject all) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(all.toString(2).getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, "could not write evidence: " + e);
        }
    }
}
