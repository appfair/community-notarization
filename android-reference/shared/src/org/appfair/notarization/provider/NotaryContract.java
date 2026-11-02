package org.appfair.notarization.provider;

import java.util.Arrays;
import java.util.List;

/**
 * Names and limits shared by the coordinator and provider apps: the field-level contract of
 * the specification (revision 9, "Provider registration and messages"), with the canonical
 * signed bytes, signature envelope, limits and AIDL types that the specification leaves to
 * the reference implementation. Everything a provider needs to interoperate is here and in
 * the AIDL files; nothing requires linking against the coordinator.
 */
public final class NotaryContract {
    /** Bumped for incompatible changes to the request, response or metadata. */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * The {@code <meta-data>} name on a provider's {@code <application>}. Its
     * {@code android:resource} points at an XML resource:
     * <pre>
     * &lt;notary protocolVersion="1" providerId="org.example.consumerreporting"
     *         transport="service" service="org.example.consumerreporting.NotaryService"
     *         name="Consumer Reporting" question="Has this app been reviewed …?"&gt;
     *   &lt;data fields="packageName,versionCode,apkSha256,signingCertificateSha256" /&gt;
     * &lt;/notary&gt;
     * </pre>
     * Attributes of {@code <notary>}:
     * <pre>
     *   protocolVersion  must be 1
     *   providerId       unique among enabled providers; reverse-DNS style
     *   transport        "service" (Binder) or "https"
     *   service          fully qualified (or ".relative") class of the bound service
     *   endpoint         the https URL (transport="https")
     *   keys             comma-separated base64 SubjectPublicKeyInfo of the ECDSA P-256 keys
     *                    that sign HTTPS responses; required for transport="https"
     *   issuerKeys       the same, for portable attestations a service may return
     *   tls              optional "sha256:&lt;hex&gt;" pin of the endpoint certificate, for test
     *                    deployments without a public CA
     *   local            "true" when the service answers from on-device data only
     *   name, question   shown in the coordinator's settings and review screens
     * </pre>
     * The {@code <data fields="…"/>} element lists the request fields the provider receives:
     * the four identity fields, optionally {@code language}, and {@code apkFile} for a
     * read-only descriptor of the staged APK (service transport only).
     */
    public static final String META_DATA = "org.appfair.notarization.PROVIDER";

    public static final String TRANSPORT_SERVICE = "service";
    public static final String TRANSPORT_HTTPS = "https";

    public static final String FIELD_PACKAGE_NAME = "packageName";
    public static final String FIELD_VERSION_CODE = "versionCode";
    public static final String FIELD_APK_SHA256 = "apkSha256";
    public static final String FIELD_SIGNING_CERTIFICATES = "signingCertificateSha256";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_APK_FILE = "apkFile";
    public static final List<String> IDENTITY_FIELDS = Arrays.asList(FIELD_PACKAGE_NAME, FIELD_VERSION_CODE,
            FIELD_APK_SHA256, FIELD_SIGNING_CERTIFICATES);
    public static final List<String> ALL_FIELDS = Arrays.asList(FIELD_PACKAGE_NAME, FIELD_VERSION_CODE,
            FIELD_APK_SHA256, FIELD_SIGNING_CERTIFICATES, FIELD_LANGUAGE, FIELD_APK_FILE);

    /** Intent action a provider's bound service must filter on. */
    public static final String ACTION_SERVICE = "org.appfair.notarization.NOTARY_SERVICE";

    /** The permission a service checks on its caller: only the platform's verifier holds it. */
    public static final String COORDINATOR_PERMISSION = "android.permission.PACKAGE_VERIFICATION_AGENT";

    /** Provider verdicts (specification, "Response"). Age 16+ is an advisory with minAge 16. */
    public static final String VERDICT_APPROVE = "approve";
    public static final String VERDICT_DENY = "deny";
    public static final String VERDICT_REVOKE = "revoke";
    public static final String VERDICT_ABSTAIN = "abstain";
    public static final String VERDICT_ADVISORY = "advisory";
    public static final List<String> VERDICTS = Arrays.asList(
            VERDICT_APPROVE, VERDICT_DENY, VERDICT_REVOKE, VERDICT_ABSTAIN, VERDICT_ADVISORY);

    /** Per-provider deadline (specification, "Decide on the device"). */
    public static final long DEADLINE_MS = 8_000;
    /** How long an assessment is reusable (specification, "Reuse evidence"). */
    public static final long EVIDENCE_LIFETIME_MS = 24L * 60 * 60 * 1000;
    /** Responses above this size are rejected before parsing. */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_EXPLANATION_CHARS = 1000;
    public static final int MAX_REASON_CODE_CHARS = 64;

    /**
     * Demonstration hook. A provider built on {@link NotaryService} reads the global setting
     * {@code notary_override_<last segment of the provider ID>} (a JSON object) before
     * answering and applies: {@code verdict}, {@code reason}, {@code delay_ms}, {@code silent}
     * (never answer), {@code crash} (kill the process), {@code invalid} (answer for the wrong
     * app), {@code offline} (behave as if the provider's own network were down).
     */
    public static final String OVERRIDE_SETTING_PREFIX = "notary_override_";

    public static String abbreviate(String digest) {
        if (digest == null) return "none";
        return digest.length() > 16 ? digest.substring(0, 16) + "…" : digest;
    }

    public static boolean isVerdict(String s) {
        return s != null && VERDICTS.contains(s);
    }

    /** The settings key suffix for a provider ID: its last dotted segment. */
    public static String shortId(String providerId) {
        int dot = providerId.lastIndexOf('.');
        return dot < 0 ? providerId : providerId.substring(dot + 1);
    }

    private NotaryContract() { }
}
