package org.appfair.notarization;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * What the user accepted when enabling a notary (specification, "Trust binds the provider ID
 * to the installed package and accepted signing identity"): the package, its signing
 * certificate digest and the data access it asked for. A later provider update that changes
 * any of these is not trusted until the notary is enabled again.
 *
 * The enabled set itself lives in the global setting
 * {@link ProviderRegistry#ENABLED_SETTING}, which stands in for the system settings page of
 * a production device; {@link SettingsActivity} writes both.
 */
public final class ProviderStore {
    static final String PREFS = "providers";

    public static final class Acceptance {
        public final String packageName, signerSha256;
        public final boolean apkAccess;
        public final long acceptedAt;

        Acceptance(String packageName, String signerSha256, boolean apkAccess, long acceptedAt) {
            this.packageName = packageName; this.signerSha256 = signerSha256;
            this.apkAccess = apkAccess; this.acceptedAt = acceptedAt;
        }

        String toJson() {
            try {
                return new JSONObject().put("package", packageName).put("signer", signerSha256)
                        .put("apk", apkAccess).put("at", acceptedAt).toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static Acceptance fromJson(String s) {
            try {
                JSONObject o = new JSONObject(s);
                return new Acceptance(o.getString("package"), o.optString("signer", null),
                        o.optBoolean("apk"), o.optLong("at"));
            } catch (Exception e) {
                return null;
            }
        }
    }

    private final SharedPreferences prefs;

    public ProviderStore(Context context) {
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Acceptance get(String id) {
        String s = prefs.getString(id, null);
        return s == null ? null : Acceptance.fromJson(s);
    }

    public void accept(String id, String packageName, String signerSha256, boolean apkAccess) {
        prefs.edit().putString(id, new Acceptance(packageName, signerSha256, apkAccess,
                System.currentTimeMillis()).toJson()).apply();
    }

    public void forget(String id) {
        prefs.edit().remove(id).apply();
    }

    public Map<String, Acceptance> all() {
        Map<String, Acceptance> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Acceptance a = Acceptance.fromJson(String.valueOf(e.getValue()));
            if (a != null) out.put(e.getKey(), a);
        }
        return out;
    }
}
