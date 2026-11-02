package org.appfair.notarization;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.provider.Settings;
import android.util.Log;

import org.appfair.notarization.provider.Attestation;
import org.appfair.notarization.provider.NotaryContract;
import org.xmlpull.v1.XmlPullParser;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The notaries on this device (specification, "Enable a notary through its app" and
 * "Registration").
 *
 * Any installed app can declare one notary with the {@link NotaryContract#META_DATA} manifest
 * entry. Installation only makes the declaration discoverable: a notary takes part in checks
 * once the user enables it, which records the package and signing identity they accepted
 * ({@link ProviderStore}). The developer-verification notary, {@link #ADV_PROVIDER_ID}, is
 * always active when its provider is installed, as the specification keeps ADV enabled.
 *
 * The enabled set is read from the global setting {@link #ENABLED_SETTING} (a comma-separated
 * list of provider IDs). On this reference image that setting is the coordinator's settings
 * store; {@link SettingsActivity} edits it and scripted scenarios set it with
 * {@code adb shell settings put global}.
 */
public final class ProviderRegistry {
    public static final String ENABLED_SETTING = "community_notarization_providers";
    /**
     * Demonstration hook. The specification exempts ADB installs from ADV. Scripted scenarios
     * are driven through adb, so this setting (1) makes the coordinator treat them like a
     * standard installation; by default the ADV notary is exempt for adb installs.
     */
    public static final String ADB_AS_STANDARD_SETTING = "community_notarization_adb_as_standard";
    /**
     * The provider ID of the developer-verification notary. In Google's deployment this would
     * name the Android Developer Verifier's declaration; here it is the simulated provider.
     */
    public static final String ADV_PROVIDER_ID = "org.appfair.notary.adv";
    static final String SHELL_PACKAGE = "com.android.shell";

    /** One declared notary, installed on this device. */
    public static final class Provider {
        public final String id, name, question, packageName, signerSha256;
        public final int version;
        public final ComponentName service;
        public final String endpoint;
        public final List<PublicKey> keys, issuerKeys;
        public final String tlsPin;
        public final Set<String> fields;
        public final boolean local, required;
        public final ProviderStore.Acceptance acceptance;
        public final boolean enabled;

        Provider(String id, int version, String name, String question, String packageName, String signerSha256,
                 ComponentName service, String endpoint, List<PublicKey> keys, List<PublicKey> issuerKeys,
                 String tlsPin, Set<String> fields, boolean local, boolean required,
                 ProviderStore.Acceptance acceptance, boolean enabled) {
            this.id = id; this.version = version; this.name = name; this.question = question;
            this.packageName = packageName; this.signerSha256 = signerSha256; this.service = service;
            this.endpoint = endpoint; this.keys = keys; this.issuerKeys = issuerKeys; this.tlsPin = tlsPin;
            this.fields = fields; this.local = local; this.required = required;
            this.acceptance = acceptance; this.enabled = enabled;
        }

        public boolean isHttps() {
            return endpoint != null;
        }

        public boolean wantsApk() {
            return fields.contains(NotaryContract.FIELD_APK_FILE);
        }

        public boolean wantsLanguage() {
            return fields.contains(NotaryContract.FIELD_LANGUAGE);
        }

        /** True when the installed app no longer matches what the user accepted. */
        public boolean identityChanged() {
            return acceptance != null && (!acceptance.packageName.equals(packageName)
                    || (acceptance.signerSha256 != null && !acceptance.signerSha256.equals(signerSha256)));
        }

        /** APK access is only granted when the user accepted it along with the provider. */
        public boolean apkAccessGranted() {
            return wantsApk() && !isHttps() && acceptance != null && acceptance.apkAccess && !identityChanged();
        }

        public String routeText() {
            if (isHttps()) return "HTTPS endpoint " + endpoint.replaceFirst("^https://", "").replaceFirst("/.*$", "");
            return local ? "App service on this device" : "App service (may use its own network)";
        }

        public String dataText() {
            List<String> shown = new ArrayList<>();
            for (String f : NotaryContract.ALL_FIELDS) if (fields.contains(f)) shown.add(f);
            return "Receives " + String.join(", ", shown) + (wantsApk() ? " (a read-only copy of the APK)" : "");
        }

        /** The part of the configuration fingerprint this provider contributes. */
        String fingerprint() {
            return id + "@" + packageName + ":" + (signerSha256 == null ? "?" : signerSha256.substring(0, 16));
        }

        @Override
        public String toString() {
            return id + " (" + packageName + ", " + (isHttps() ? "https" : local ? "local service" : "service") + ")";
        }
    }

    /** The outcome of selecting participants for one check. */
    public static final class Active {
        public final List<Provider> providers;
        /** True when the ADV notary was installed but skipped as exempt for this install. */
        public final boolean advExempt;

        Active(List<Provider> providers, boolean advExempt) {
            this.providers = providers; this.advExempt = advExempt;
        }

        /** The ADV-only baseline applies: the required notary is the only participant. */
        public boolean advOnly() {
            return providers.size() == 1 && providers.get(0).required;
        }
    }

    public final List<Provider> installed;

    private ProviderRegistry(List<Provider> installed) {
        this.installed = Collections.unmodifiableList(installed);
    }

    /** Everything declared on the device, with its enabled and accepted state. */
    public static ProviderRegistry discover(Context context) {
        PackageManager pm = context.getPackageManager();
        ProviderStore store = new ProviderStore(context);
        Set<String> enabled = enabledIds(context);
        List<Provider> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (ai.metaData == null || !ai.metaData.containsKey(NotaryContract.META_DATA)) continue;
            try {
                Provider p = parse(pm, store, enabled, ai, ai.metaData.getInt(NotaryContract.META_DATA));
                if (!seen.add(p.id)) {
                    Log.w(VerificationReceiver.TAG, "ignoring " + ai.packageName + ": provider ID " + p.id
                            + " is already declared by another installed app");
                    continue;
                }
                out.add(p);
            } catch (Exception e) {
                Log.w(VerificationReceiver.TAG, "ignoring notary declaration of " + ai.packageName + ": " + e);
            }
        }
        out.sort((a, b) -> a.required != b.required ? (a.required ? -1 : 1) : a.id.compareTo(b.id));
        return new ProviderRegistry(out);
    }

    /** The participants of a check: the required notary (unless exempt) and the enabled ones. */
    public Active active(Context context, String installer) {
        boolean adbStandard = Settings.Global.getInt(context.getContentResolver(), ADB_AS_STANDARD_SETTING, 0) == 1;
        List<Provider> out = new ArrayList<>();
        boolean exempt = false;
        for (Provider p : installed) {
            if (!(p.required || p.enabled)) continue;
            if (p.required && SHELL_PACKAGE.equals(installer) && !adbStandard) {
                Log.i(VerificationReceiver.TAG, "adb install: the " + p.id + " notary is exempt (set "
                        + ADB_AS_STANDARD_SETTING + "=1 to include it)");
                exempt = true;
                continue;
            }
            out.add(p);
        }
        return new Active(out, exempt);
    }

    public Provider byId(String id) {
        for (Provider p : installed) if (p.id.equals(id)) return p;
        return null;
    }

    /** A stable description of the configuration a check ran under; evidence is bound to it. */
    public static String configurationFingerprint(List<Provider> active) {
        List<String> parts = new ArrayList<>();
        for (Provider p : active) parts.add(p.fingerprint());
        Collections.sort(parts);
        return String.join(",", parts);
    }

    public static Set<String> enabledIds(Context context) {
        String csv = Settings.Global.getString(context.getContentResolver(), ENABLED_SETTING);
        Set<String> ids = new HashSet<>();
        if (csv != null) for (String s : csv.split(",")) if (!s.trim().isEmpty()) ids.add(s.trim());
        return ids;
    }

    public static void setEnabledIds(Context context, Set<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        Settings.Global.putString(context.getContentResolver(), ENABLED_SETTING, String.join(",", sorted));
    }

    private static Provider parse(PackageManager pm, ProviderStore store, Set<String> enabled,
                                  ApplicationInfo ai, int resId) throws Exception {
        Resources res = pm.getResourcesForApplication(ai);
        try (XmlResourceParser x = res.getXml(resId)) {
            int event;
            while ((event = x.next()) != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) { }
            if (event != XmlPullParser.START_TAG || !"notary".equals(x.getName())) {
                throw new IllegalArgumentException("root element is not <notary>");
            }
            int version = Integer.parseInt(attr(x, "protocolVersion", "0"));
            if (version != NotaryContract.PROTOCOL_VERSION) {
                throw new IllegalArgumentException("unsupported protocolVersion " + version);
            }
            String id = attr(x, "providerId");
            if (id == null || !id.matches("[a-z][a-z0-9]*(\\.[a-z0-9_-]+)*") || id.length() > 128) {
                throw new IllegalArgumentException("bad providerId " + id);
            }
            String name = attr(x, "name", id);
            String question = attr(x, "question", "");
            String transport = attr(x, "transport", "");
            String serviceName = attr(x, "service");
            String endpoint = attr(x, "endpoint");
            switch (transport) {
                case NotaryContract.TRANSPORT_SERVICE:
                    if (serviceName == null) throw new IllegalArgumentException("transport=service needs service=");
                    endpoint = null;
                    break;
                case NotaryContract.TRANSPORT_HTTPS:
                    if (endpoint == null || !endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
                        throw new IllegalArgumentException("transport=https needs an https endpoint=");
                    }
                    serviceName = null;
                    break;
                default:
                    throw new IllegalArgumentException("transport must be service or https");
            }
            List<PublicKey> keys = parseKeys(attr(x, "keys"));
            List<PublicKey> issuerKeys = parseKeys(attr(x, "issuerKeys"));
            if (endpoint != null && keys.isEmpty()) throw new IllegalArgumentException("an endpoint needs keys=");
            String tlsPin = attr(x, "tls");
            if (tlsPin != null) tlsPin = tlsPin.replaceFirst("^sha256:", "").toLowerCase(Locale.ROOT);
            boolean local = "true".equals(attr(x, "local", "false"));

            Set<String> fields = new LinkedHashSet<>(NotaryContract.IDENTITY_FIELDS);
            int depth = 1;
            while (depth > 0 && (event = x.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    if ("data".equals(x.getName())) {
                        String list = attr(x, "fields", "");
                        for (String f : list.split(",")) {
                            f = f.trim();
                            if (f.isEmpty()) continue;
                            if (!NotaryContract.ALL_FIELDS.contains(f)) throw new IllegalArgumentException("unknown field " + f);
                            fields.add(f);
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    depth--;
                }
            }
            if (endpoint != null && fields.contains(NotaryContract.FIELD_APK_FILE)) {
                throw new IllegalArgumentException("an https endpoint cannot receive the APK");
            }

            ComponentName service = serviceName == null ? null
                    : new ComponentName(ai.packageName, serviceName.startsWith(".") ? ai.packageName + serviceName : serviceName);
            String signer = signerOf(pm, ai.packageName);
            boolean required = ADV_PROVIDER_ID.equals(id);
            boolean isEnabled = enabled.contains(id);
            boolean wantsApk = fields.contains(NotaryContract.FIELD_APK_FILE);
            ProviderStore.Acceptance acceptance = store.get(id);
            if ((isEnabled || required) && acceptance == null) {
                // Enabled outside the settings screen (adb, or a required notary): the identity
                // and data access declared right now are what is being trusted. Bind to them.
                store.accept(id, ai.packageName, signer, wantsApk);
                acceptance = store.get(id);
                Log.i(VerificationReceiver.TAG, "bound notary " + id + " to " + ai.packageName + " (signer "
                        + ApkFacts.abbreviate(signer) + (wantsApk ? ", APK access)" : ")"));
            }
            return new Provider(id, version, name, question, ai.packageName, signer, service, endpoint,
                    keys, issuerKeys, tlsPin, fields, local, required, acceptance, isEnabled);
        }
    }

    private static List<PublicKey> parseKeys(String csv) throws Exception {
        List<PublicKey> keys = new ArrayList<>();
        if (csv == null) return keys;
        for (String k : csv.split(",")) {
            if (!k.trim().isEmpty()) keys.add(Attestation.publicKey(k.trim()));
        }
        return keys;
    }

    private static String attr(XmlResourceParser x, String name) {
        return x.getAttributeValue(null, name);
    }

    private static String attr(XmlResourceParser x, String name, String def) {
        String v = attr(x, name);
        return v == null ? def : v;
    }

    /** SHA-256 of the package's current signing certificate, or null if not installed. */
    static String signerOf(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return ApkFacts.signerDigest(info.signingInfo);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static List<String> describe(List<Provider> list) {
        List<String> out = new ArrayList<>();
        for (Provider p : list) out.add(p.id);
        return out;
    }
}
