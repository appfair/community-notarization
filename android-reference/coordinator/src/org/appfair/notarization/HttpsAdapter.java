package org.appfair.notarization;

import android.util.Log;

import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The HTTPS adapter (specification, "Privacy and accountability"): posts only the approved
 * request fields to the declared endpoint, refuses redirects to any other recipient, bounds
 * the response size, and accepts only an attestation signed with a declared key and wrapped
 * in a live-response envelope for this request ID.
 *
 * Wire format (specification, "Provider registration and messages"):
 * <pre>
 * request:  {"protocolVersion":1,"requestId":"…","app":{"packageName":"…","versionCode":"1",
 *            "apkSha256":"…","signingCertificateSha256":["…"]},"language":"en"}
 * response: {"protocolVersion":1,"requestId":"…","providerId":"…","app":{…},"verdict":"approve",
 *            "reasonCode":"NO_CONCERNS","explanation":"…","minAge":16,"issuedAt":"2026-11-02T18:00:00Z",
 *            "expiresAt":"2026-11-03T18:00:00Z","attestationSignature":"…","envelopeSignature":"…"}
 * </pre>
 *
 * Transport trust is the platform's CA store, or, when the declaration pins a certificate
 * digest, exactly that certificate; the pin exists so that a test endpoint without a public
 * CA can be used. Neither replaces the attestation signature, which is checked in every case.
 */
final class HttpsAdapter {
    static Assessor.Live assess(ProviderRegistry.Provider p, NotaryRequest request, ApkFacts facts) {
        long t0 = System.currentTimeMillis();
        HttpsURLConnection c = null;
        try {
            URL url = new URL(p.endpoint);
            if (!"https".equals(url.getProtocol())) {
                return Assessor.Live.problem(p, Assessment.Verdict.INVALID, "The endpoint is not https.", t0);
            }
            c = (HttpsURLConnection) url.openConnection();
            if (p.tlsPin != null) {
                c.setSSLSocketFactory(pinned(p.tlsPin));
                c.setHostnameVerifier((host, session) -> true);   // the pin identifies the server
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(3_000);
            c.setReadTimeout((int) NotaryContract.DEADLINE_MS);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (request.language != null) c.setRequestProperty("Accept-Language", request.language);
            c.setDoOutput(true);
            byte[] body = requestJson(request).getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                return Assessor.Live.problem(p, Assessment.Verdict.INVALID,
                        "The endpoint tried to redirect the request elsewhere.", t0);
            }
            if (code != 200) {
                return Assessor.Live.problem(p, code / 100 == 5 ? Assessment.Verdict.UNAVAILABLE : Assessment.Verdict.INVALID,
                        "The endpoint answered HTTP " + code + ".", t0);
            }
            byte[] payload = readBounded(c.getInputStream(), NotaryContract.MAX_PAYLOAD_BYTES);
            if (payload == null) {
                return Assessor.Live.problem(p, Assessment.Verdict.INVALID, "The response was too large.", t0);
            }
            NotaryAssessment a = parse(new JSONObject(new String(payload, StandardCharsets.UTF_8)));
            String error = Assessor.validate(p, request, facts, a, true);
            if (error != null) {
                Log.w(VerificationReceiver.TAG, p.id + ": invalid response: " + error);
                return Assessor.Live.problem(p, Assessment.Verdict.INVALID, null, t0);
            }
            return Assessor.accept(p, a, t0);
        } catch (SocketTimeoutException e) {
            return Assessor.Live.problem(p, Assessment.Verdict.TIMEOUT, null, t0);
        } catch (IOException e) {
            Log.w(VerificationReceiver.TAG, p.id + ": " + e);
            return Assessor.Live.problem(p, Assessment.Verdict.UNAVAILABLE, null, t0);
        } catch (Exception e) {
            Log.w(VerificationReceiver.TAG, p.id + ": invalid response: " + e);
            return Assessor.Live.problem(p, Assessment.Verdict.INVALID, null, t0);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The request body: exactly the fields of the specification's request, nothing else. */
    static String requestJson(NotaryRequest r) throws Exception {
        JSONArray signers = new JSONArray();
        for (String s : r.signingCertificateSha256) signers.put(s);
        JSONObject app = new JSONObject().put(NotaryContract.FIELD_PACKAGE_NAME, r.packageName)
                .put(NotaryContract.FIELD_VERSION_CODE, Long.toString(r.versionCode))
                .put(NotaryContract.FIELD_APK_SHA256, r.apkSha256)
                .put(NotaryContract.FIELD_SIGNING_CERTIFICATES, signers);
        JSONObject o = new JSONObject().put("protocolVersion", r.protocolVersion).put("requestId", r.requestId)
                .put("app", app);
        if (r.language != null) o.put(NotaryContract.FIELD_LANGUAGE, r.language);
        return o.toString();
    }

    static NotaryAssessment parse(JSONObject o) {
        JSONObject app = o.optJSONObject("app");
        if (app == null) app = new JSONObject();
        JSONArray certs = app.optJSONArray(NotaryContract.FIELD_SIGNING_CERTIFICATES);
        String[] signers = new String[certs == null ? 0 : certs.length()];
        for (int i = 0; i < signers.length; i++) signers[i] = certs.optString(i);
        long versionCode;
        try {
            versionCode = Long.parseLong(app.optString(NotaryContract.FIELD_VERSION_CODE, "x"));
        } catch (NumberFormatException e) {
            versionCode = -1;
        }
        return new NotaryAssessment(o.optInt("protocolVersion", -1), o.optString("providerId", null),
                o.optString("requestId", null), app.optString(NotaryContract.FIELD_PACKAGE_NAME, null), versionCode,
                app.optString(NotaryContract.FIELD_APK_SHA256, null), signers,
                o.optString("verdict", null), o.optString("reasonCode", ""), o.optString("explanation", ""),
                o.optInt("minAge", 0), o.optString("issuedAt", ""), o.optString("expiresAt", ""),
                o.optString("attestationSignature", null), o.optString("envelopeSignature", null));
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > max) return null;
        }
        return buf.toByteArray();
    }

    private static SSLSocketFactory pinned(String sha256Hex) throws Exception {
        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    String got = ApkFacts.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                    if (!got.equalsIgnoreCase(sha256Hex)) throw new CertificateException("certificate pin mismatch");
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx.getSocketFactory();
    }

    private HttpsAdapter() { }
}
