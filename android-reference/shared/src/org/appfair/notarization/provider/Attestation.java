package org.appfair.notarization.provider;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The signed forms of an assessment (specification, "Authentication"): ECDSA P-256
 * (SHA256withECDSA) over a versioned canonical payload. This class fixes the canonical bytes
 * and the envelope that the specification leaves to the reference implementation.
 *
 * <ul>
 *   <li>A <b>portable attestation</b> is the response's fields without the request ID and the
 *       signatures: it binds a verdict to an exact app identity and an expiry, so a store or a
 *       provider app can carry it without a live request.</li>
 *   <li>A <b>live-response envelope</b> binds an attestation's signature to one request ID, so
 *       a fresh HTTPS answer cannot be replayed for another request.</li>
 * </ul>
 *
 * Canonical JSON: object keys in code-point order at every level, no whitespace, minimal
 * string escaping ({@code " \ \n \r \t} and other control characters as {@code \\uXXXX}),
 * non-ASCII characters unescaped, integers in decimal. {@code json.dumps(obj, sort_keys=True,
 * separators=(",", ":"), ensure_ascii=False)} produces the same bytes
 * (scripts/notary-server.py). Each payload is prefixed with a version line.
 */
public final class Attestation {
    public static final String ATTESTATION_HEADER = "community-notarization/attestation/1\n";
    public static final String ENVELOPE_HEADER = "community-notarization/envelope/1\n";

    /** The bytes a provider signs and the coordinator verifies for a portable attestation. */
    public static byte[] canonical(NotaryAssessment a) {
        return (ATTESTATION_HEADER + json(a.toMap())).getBytes(StandardCharsets.UTF_8);
    }

    /** The bytes signed for the live-response envelope. */
    public static byte[] canonicalEnvelope(String requestId, String attestationSignature) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("requestId", requestId);
        m.put("attestationSignature", attestationSignature);
        return (ENVELOPE_HEADER + json(m)).getBytes(StandardCharsets.UTF_8);
    }

    public static NotaryAssessment sign(NotaryAssessment a, PrivateKey key, boolean envelope) throws Exception {
        String att = Base64.getEncoder().encodeToString(sign(canonical(a), key));
        String env = envelope && a.requestId != null
                ? Base64.getEncoder().encodeToString(sign(canonicalEnvelope(a.requestId, att), key)) : null;
        return a.withSignatures(att, env);
    }

    /** True when some accepted key signed the attestation and, if present, its envelope. */
    public static boolean verify(NotaryAssessment a, List<PublicKey> keys, boolean requireEnvelope) {
        if (a.attestationSignature == null || keys.isEmpty()) return false;
        for (PublicKey key : keys) {
            try {
                if (!verify(canonical(a), Base64.getDecoder().decode(a.attestationSignature), key)) continue;
                if (a.envelopeSignature == null) return !requireEnvelope;
                return a.requestId != null && verify(canonicalEnvelope(a.requestId, a.attestationSignature),
                        Base64.getDecoder().decode(a.envelopeSignature), key);
            } catch (Exception ignored) {
                // a malformed signature or key simply does not verify
            }
        }
        return false;
    }

    public static byte[] sign(byte[] data, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    public static boolean verify(byte[] data, byte[] signature, PublicKey key) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(data);
        return s.verify(signature);
    }

    /** Base64 X.509 SubjectPublicKeyInfo (what {@code openssl ec -pubout -outform DER} writes). */
    public static PublicKey publicKey(String base64) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    /** Base64 PKCS#8 (what {@code openssl pkcs8 -topk8 -nocrypt -outform DER} writes). */
    public static PrivateKey privateKey(String base64) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    /** Canonical JSON of a tree of sorted maps, lists, strings, integers, booleans and nulls. */
    public static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : new TreeMap<>((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private Attestation() { }
}
