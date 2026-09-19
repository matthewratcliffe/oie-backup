/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4, for the six S3 calls this plugin makes.
 *
 * <p>Hand-written, like the one in the Key Store extension, and for the same reason: the
 * engine ships AWS SDK 2.15.28 in {@code server-lib/aws}, but what an extension's
 * classloader can actually see from there depends on how the engine was launched, and a
 * backup destination that stops working because the engine reorganised its libraries is
 * exactly the failure this plugin must not have. SigV4 has not changed since 2012.
 *
 * <p>This one is a little more than the Key Store's, because S3 needs two things Secrets
 * Manager does not: a canonical query string, for the listing and every multipart call, and
 * a payload hash taken over a file rather than a string, for the upload itself.
 *
 * <h2>Why the payload is hashed rather than declared unsigned</h2>
 *
 * <p>S3 accepts {@code UNSIGNED-PAYLOAD} over HTTPS and it would save a pass over the file.
 * The real hash is sent anyway because it is the only end-to-end check in the whole
 * upload path: S3 verifies the body it received against the hash the signature covers and
 * refuses the object if they differ. A backup that arrives corrupted and is accepted is the
 * failure mode this plugin exists to prevent, and one sequential read of a local file --
 * which was just written, so it is in page cache -- is a cheap way to close it.
 */
final class AwsV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";

    /** The hash S3 wants for a request that carries no body at all. */
    static final String EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsV4Signer() {
    }

    /**
     * Signs a request and returns every header it must be sent with.
     *
     * <p>The returned map includes the headers that were signed, because a signed header
     * that is not sent -- or is sent with a different value -- fails with the same opaque
     * "signature we calculated does not match" as a wrong secret key. Handing back the
     * complete set is the only way to keep the two in step.
     *
     * @param uri          the full request URI; its raw query is canonicalised here
     * @param extraHeaders headers to sign beyond host, date and the content hash
     * @param payloadSha256 lowercase hex SHA-256 of the body, or
     *                     {@link #EMPTY_PAYLOAD_SHA256} when there is none
     */
    static Map<String, String> sign(String method, URI uri, String region, String service,
                                    Map<String, String> extraHeaders, String payloadSha256,
                                    String accessKeyId, String secretAccessKey,
                                    String sessionToken) {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);

        // Sorted and lowercased, because the canonical request and the SignedHeaders list
        // must agree on order and case; a TreeMap makes that automatic rather than
        // something to remember in two places.
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-content-sha256", payloadSha256);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getValue() != null) {
                    signed.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim());
                }
            }
        }
        if (sessionToken != null && !sessionToken.isEmpty()) {
            // Temporary credentials are only accepted when the token is part of the
            // signature, not merely sent alongside it.
            signed.put("x-amz-security-token", sessionToken);
        }

        String canonicalHeaders = canonicalHeaders(signed);
        String signedHeaderNames = String.join(";", signed.keySet());

        String canonicalRequest = String.join("\n",
            method,
            canonicalPath(uri),
            canonicalQuery(uri.getRawQuery()),
            canonicalHeaders,
            signedHeaderNames,
            payloadSha256);

        String scope = dateStamp + "/" + region + "/" + service + "/" + TERMINATOR;
        String stringToSign = String.join("\n",
            ALGORITHM, amzDate, scope, hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8))));

        byte[] signingKey = signingKey(secretAccessKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> out = new LinkedHashMap<>(signed);
        out.put("Authorization", ALGORITHM
            + " Credential=" + accessKeyId + "/" + scope
            + ", SignedHeaders=" + signedHeaderNames
            + ", Signature=" + signature);
        return out;
    }

    /**
     * The Host header as the JDK's client will actually send it.
     *
     * <p>The default port is left off. Including {@code :443} here while the client sends a
     * bare host is a signature mismatch, and it is not obvious from the error which of the
     * two sides is wrong. A MinIO on {@code :9000} does get its port, because the client
     * sends it.
     */
    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
            || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
            || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()));
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static String canonicalHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(':')
              .append(e.getValue().trim().replaceAll("\\s+", " "))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * The path, already encoded by the caller.
     *
     * <p>S3 is the one service that does <b>not</b> double-encode the path in the canonical
     * request, so the raw path is used as-is and {@link #uriEncode} is applied once, by
     * whoever built the key. Encoding again here is the classic way to make every object
     * whose name contains a space fail to sign.
     */
    private static String canonicalPath(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /**
     * Query parameters sorted by name, then by value, each encoded, joined with {@code &}.
     *
     * <p>A parameter with no value -- {@code ?uploads}, which S3 uses to start a multipart
     * upload -- canonicalises as {@code uploads=}, with the trailing equals. Leaving it off
     * is a signature mismatch on the one call it affects.
     */
    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        TreeMap<String, TreeMap<String, String>> sorted = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            // Decoded and re-encoded so the canonical form is ours regardless of how the
            // caller spelled it; the raw query here is always one this class built.
            String decodedName = decode(name);
            String decodedValue = decode(value);
            sorted.computeIfAbsent(uriEncode(decodedName, false), k -> new TreeMap<>())
                .put(uriEncode(decodedValue, false), "");
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((name, values) -> values.keySet().forEach(value -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(name).append('=').append(value);
        }));
        return sb.toString();
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * RFC 3986 unreserved-only encoding, which is what SigV4 means by URI-encoding.
     *
     * <p>Not {@link java.net.URLEncoder}: that produces {@code +} for a space and leaves
     * {@code *} alone, both of which are wrong here and both of which produce a signature
     * mismatch rather than an error that names the cause.
     *
     * @param keepSlashes true for an object key, where {@code /} is a separator
     */
    static String uriEncode(String value, boolean keepSlashes) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '~' || c == '.'
                || (keepSlashes && c == '/')) {
                sb.append(c);
            } else {
                sb.append('%')
                  .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                  .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static byte[] signingKey(String secretAccessKey, String dateStamp, String region,
                                     String service) {
        byte[] key = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        return hmac(hmac(hmac(hmac(key, dateStamp), region), service), TERMINATOR);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // HmacSHA256 is required of every JRE. If it is genuinely missing, nothing this
            // plugin does can work and there is no sensible fallback to attempt.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * Fixed vectors from the AWS documentation's own SigV4 test suite, run by
     * {@code build.sh}.
     *
     * <p>A wrong signature fails with "the request signature we calculated does not match",
     * which says nothing about which of the ten inputs was wrong. Asserting the canonical
     * forms at build time turns that into a named failure here instead.
     *
     * @return null when every vector holds, otherwise the first failure
     */
    static String selfTest() {
        String got = canonicalQuery("uploads");
        if (!"uploads=".equals(got)) {
            return "a valueless parameter must canonicalise with a trailing '=', got '"
                + got + "'";
        }
        got = canonicalQuery("partNumber=2&uploadId=abc");
        if (!"partNumber=2&uploadId=abc".equals(got)) {
            return "sorted parameters, got '" + got + "'";
        }
        got = canonicalQuery("uploadId=abc&partNumber=2");
        if (!"partNumber=2&uploadId=abc".equals(got)) {
            return "parameters must sort by name, got '" + got + "'";
        }
        got = canonicalQuery("list-type=2&prefix=a%20b");
        if (!"list-type=2&prefix=a%20b".equals(got)) {
            return "a space in a value must encode as %20, got '" + got + "'";
        }
        got = uriEncode("a b/c*d", true);
        if (!"a%20b/c%2Ad".equals(got)) {
            return "key encoding must keep '/', encode ' ' and encode '*', got '" + got + "'";
        }
        got = hex(sha256(new byte[0]));
        if (!EMPTY_PAYLOAD_SHA256.equals(got)) {
            return "the empty payload hash constant is wrong";
        }
        // The documented example from the SigV4 signing-key derivation page.
        got = hex(signingKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "20150830", "us-east-1", "iam"));
        if (!"c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9".equals(got)) {
            return "the signing-key derivation does not match the documented vector";
        }
        return null;
    }
}
