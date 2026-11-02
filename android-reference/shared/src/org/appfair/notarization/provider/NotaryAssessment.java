package org.appfair.notarization.provider;

import android.os.Parcel;
import android.os.Parcelable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * A notary's answer (specification, "Response"): provider ID, request ID, exact app identity,
 * verdict, reason code, optional localized explanation, issue time and expiry. Verdicts are
 * approve, deny, revoke, abstain or advisory; an age advisory carries {@code minAge}.
 *
 * Times are ISO-8601 UTC strings with whole seconds ({@code 2026-11-02T18:00:00Z}), kept as
 * strings so that the bytes a provider signed are exactly the bytes the coordinator checks.
 *
 * A bound service's answer is authenticated by Binder and needs no signature. A service may
 * instead return a portable attestation, signed with one of the issuer keys declared in its
 * metadata ({@link #attestationSignature}). HTTPS endpoints always return attestations wrapped
 * in a live-response envelope that also binds them to the request ID
 * ({@link #envelopeSignature}). See {@link Attestation}.
 */
public final class NotaryAssessment implements Parcelable {
    public final int protocolVersion;
    public final String providerId;
    /** The request this answers; null in a stored portable attestation. */
    public final String requestId;
    public final String packageName;
    public final long versionCode;
    public final String apkSha256;
    public final String[] signingCertificateSha256;
    /** One of {@link NotaryContract#VERDICTS}. */
    public final String verdict;
    public final String reasonCode;
    public final String explanation;
    /** For an advisory: the recommended minimum age, or 0. */
    public final int minAge;
    public final String issuedAt;
    public final String expiresAt;
    public final String attestationSignature;
    public final String envelopeSignature;

    public NotaryAssessment(int protocolVersion, String providerId, String requestId, String packageName,
                            long versionCode, String apkSha256, String[] signingCertificateSha256,
                            String verdict, String reasonCode, String explanation, int minAge,
                            String issuedAt, String expiresAt, String attestationSignature, String envelopeSignature) {
        this.protocolVersion = protocolVersion;
        this.providerId = providerId;
        this.requestId = requestId;
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.apkSha256 = apkSha256;
        this.signingCertificateSha256 = signingCertificateSha256 == null ? new String[0] : signingCertificateSha256;
        this.verdict = verdict;
        this.reasonCode = reasonCode;
        this.explanation = explanation;
        this.minAge = minAge;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.attestationSignature = attestationSignature;
        this.envelopeSignature = envelopeSignature;
    }

    /** A plain, unsigned answer to a request, valid for the specification's 24 hours. */
    public static NotaryAssessment answer(String providerId, NotaryRequest request, String verdict,
                                          String reasonCode, String explanation) {
        return answer(providerId, request, verdict, reasonCode, explanation, 0);
    }

    public static NotaryAssessment answer(String providerId, NotaryRequest request, String verdict,
                                          String reasonCode, String explanation, int minAge) {
        long now = System.currentTimeMillis();
        return new NotaryAssessment(NotaryContract.PROTOCOL_VERSION, providerId, request.requestId,
                request.packageName, request.versionCode, request.apkSha256, request.signingCertificateSha256,
                verdict, reasonCode, explanation, minAge, isoTime(now), isoTime(now + NotaryContract.EVIDENCE_LIFETIME_MS),
                null, null);
    }

    public NotaryAssessment withSignatures(String attestation, String envelope) {
        return new NotaryAssessment(protocolVersion, providerId, requestId, packageName, versionCode, apkSha256,
                signingCertificateSha256, verdict, reasonCode, explanation, minAge, issuedAt, expiresAt,
                attestation, envelope);
    }

    public NotaryAssessment withPackageName(String otherPackage) {
        return new NotaryAssessment(protocolVersion, providerId, requestId, otherPackage, versionCode, apkSha256,
                signingCertificateSha256, verdict, reasonCode, explanation, minAge, issuedAt, expiresAt,
                attestationSignature, envelopeSignature);
    }

    /** ISO-8601 UTC with whole seconds: the one form both signer and verifier produce. */
    public static String isoTime(long epochMillis) {
        return Instant.ofEpochSecond(epochMillis / 1000).toString();
    }

    /** Epoch milliseconds of an ISO-8601 time, or -1 when unparseable. */
    public static long parseTime(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    public long expiresAtMillis() {
        return parseTime(expiresAt);
    }

    public long issuedAtMillis() {
        return parseTime(issuedAt);
    }

    /**
     * The fields of the JSON form, as a sorted map (nested {@code app}); the signatures and the
     * request ID are added by the transports and are never part of the signed attestation.
     */
    public TreeMap<String, Object> toMap() {
        TreeMap<String, Object> app = new TreeMap<>();
        app.put(NotaryContract.FIELD_PACKAGE_NAME, packageName);
        app.put(NotaryContract.FIELD_VERSION_CODE, Long.toString(versionCode));
        app.put(NotaryContract.FIELD_APK_SHA256, apkSha256);
        app.put(NotaryContract.FIELD_SIGNING_CERTIFICATES, new ArrayList<Object>(Arrays.asList(signingCertificateSha256)));
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("protocolVersion", protocolVersion);
        m.put("providerId", providerId);
        m.put("app", app);
        m.put("verdict", verdict);
        m.put("reasonCode", reasonCode);
        if (explanation != null && !explanation.isEmpty()) m.put("explanation", explanation);
        if (minAge > 0) m.put("minAge", minAge);
        m.put("issuedAt", issuedAt);
        m.put("expiresAt", expiresAt);
        return m;
    }

    private NotaryAssessment(Parcel in) {
        protocolVersion = in.readInt();
        providerId = in.readString();
        requestId = in.readString();
        packageName = in.readString();
        versionCode = in.readLong();
        apkSha256 = in.readString();
        signingCertificateSha256 = in.createStringArray();
        verdict = in.readString();
        reasonCode = in.readString();
        explanation = in.readString();
        minAge = in.readInt();
        issuedAt = in.readString();
        expiresAt = in.readString();
        attestationSignature = in.readString();
        envelopeSignature = in.readString();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocolVersion);
        out.writeString(providerId);
        out.writeString(requestId);
        out.writeString(packageName);
        out.writeLong(versionCode);
        out.writeString(apkSha256);
        out.writeStringArray(signingCertificateSha256);
        out.writeString(verdict);
        out.writeString(reasonCode);
        out.writeString(explanation);
        out.writeInt(minAge);
        out.writeString(issuedAt);
        out.writeString(expiresAt);
        out.writeString(attestationSignature);
        out.writeString(envelopeSignature);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NotaryAssessment> CREATOR = new Creator<NotaryAssessment>() {
        @Override public NotaryAssessment createFromParcel(Parcel in) { return new NotaryAssessment(in); }
        @Override public NotaryAssessment[] newArray(int size) { return new NotaryAssessment[size]; }
    };

    @Override
    public String toString() {
        return "NotaryAssessment{" + providerId + " " + verdict + (minAge > 0 ? " minAge=" + minAge : "") + "/"
                + reasonCode + " for " + packageName + ":" + versionCode
                + (attestationSignature != null ? " signed" : "") + "}";
    }

    static List<String> list(String[] a) {
        return Arrays.asList(a);
    }
}
