/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Amazon S3, and everything that speaks its API.
 *
 * <p>DigitalOcean Spaces, MinIO, Wasabi, Backblaze B2's S3 endpoint, Ceph RADOS Gateway and
 * Cloudflare R2 are all the same destination with a different {@code endpoint}: the
 * protocol is S3's, the signature is SigV4, and the only thing that varies is whether the
 * bucket goes in the host name or the path. So there is one class, one endpoint field, and
 * a {@code pathStyle} switch -- rather than five near-identical classes that would drift
 * apart the first time one of them needed a fix.
 *
 * <p>Written against the JDK's {@link HttpClient} and {@link AwsV4Signer} rather than the
 * AWS SDK the engine ships. The reasoning is in {@link AwsV4Signer}; the short version is
 * that an extension must not depend on the layout of {@code server-lib}.
 *
 * <h2>Large archives</h2>
 *
 * <p>A single {@code PUT} is capped at 5 GiB by S3 and by most of its imitators, and an
 * engine with a year of messages in it passes that without anybody deciding to. So uploads
 * above {@link #MULTIPART_THRESHOLD} go through a multipart upload, in
 * {@link #PART_SIZE} parts, and a failure part-way aborts the upload rather than leaving
 * the parts to sit in the bucket accruing storage charges that do not appear in any
 * listing. That last detail is the one that bites people: an aborted multipart upload is
 * invisible to {@code ls} and is billed until a lifecycle rule removes it.
 */
final class S3Destination implements Destination {

    /** Above this, a multipart upload. Comfortably inside every implementation's limit. */
    private static final long MULTIPART_THRESHOLD = 256L * 1024 * 1024;
    /**
     * 64 MiB parts: 10,000 of them is 640 GiB, which is past anything this plugin should be
     * producing, and each retry on a flaky link re-sends 64 MiB rather than the whole file.
     */
    private static final long PART_SIZE = 64L * 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /**
     * Generous, and deliberately so: this is the timeout on a single request, and one
     * request here can be a 64 MiB part going to another continent. Short enough that a
     * black-holed endpoint does not hold a scheduler thread forever.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private final HttpClient http;
    private final String bucket;
    private final String prefix;
    private final String region;
    private final URI endpoint;
    private final boolean pathStyle;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String storageClass;
    private final String sse;
    private final String kmsKeyId;

    S3Destination(DestinationConfig config) {
        this.bucket = config.require(Destinations.K_BUCKET, "The bucket");
        this.prefix = Destinations.normalisePrefix(config.get(Destinations.K_PREFIX, ""));
        this.region = config.get(Destinations.K_REGION, "us-east-1");
        this.accessKeyId = config.require(Destinations.K_ACCESS_KEY, "The access key id");
        this.secretAccessKey = config.require(Destinations.K_SECRET, "The secret access key");
        this.sessionToken = config.get("sessionToken", "");
        this.storageClass = config.get(Destinations.K_STORAGE_CLASS, "");
        this.sse = config.get(Destinations.K_SSE, "");
        this.kmsKeyId = config.get(Destinations.K_SSE_KMS_KEY, "");

        String configured = config.get(Destinations.K_ENDPOINT, "");
        if (configured.isEmpty()) {
            this.endpoint = URI.create("https://s3." + region + ".amazonaws.com");
            // Amazon deprecated path-style addressing; virtual-hosted is the default there.
            this.pathStyle = config.getBool(Destinations.K_PATH_STYLE, false);
        } else {
            String e = configured.contains("://") ? configured : "https://" + configured;
            while (e.endsWith("/")) {
                e = e.substring(0, e.length() - 1);
            }
            this.endpoint = URI.create(e);
            /*
             * Path style by default for a custom endpoint, because it is the form that
             * always works. Virtual-hosted addressing against a self-hosted MinIO needs a
             * wildcard DNS record and a wildcard certificate, and when they are missing the
             * failure is an unresolvable host or a certificate mismatch rather than
             * anything mentioning addressing style.
             */
            this.pathStyle = config.getBool(Destinations.K_PATH_STYLE, true);
        }

        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // Never followed: a 302 on a signed request would send the Authorization header
            // to whatever host the response names.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public String describe() {
        String where = endpoint.getHost() + "/" + bucket
            + (prefix.isEmpty() ? "" : "/" + prefix);
        return "S3: " + where + " (" + region + ")";
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    @Override
    public void put(String name, Path file) throws IOException {
        String key = Destinations.join(prefix, Destinations.requireSimpleName(name));
        long size = Files.size(file);
        if (size > MULTIPART_THRESHOLD) {
            multipartPut(key, file, size);
        } else {
            singlePut(key, file, size);
        }
    }

    private void singlePut(String key, Path file, long size) throws IOException {
        Map<String, String> headers = objectHeaders();
        headers.put("content-length", Long.toString(size));
        Response r = send("PUT", uriFor(key, null), headers,
            HttpRequest.BodyPublishers.ofFile(file), sha256OfFile(file),
            HttpResponse.BodyHandlers.ofString());
        if (!r.ok()) {
            throw new IOException(explain("upload", r));
        }
    }

    private void multipartPut(String key, Path file, long size) throws IOException {
        Map<String, String> start = objectHeaders();
        Response begin = send("POST", uriFor(key, "uploads="), start,
            HttpRequest.BodyPublishers.noBody(), AwsV4Signer.EMPTY_PAYLOAD_SHA256,
            HttpResponse.BodyHandlers.ofString());
        if (!begin.ok()) {
            throw new IOException(explain("start a multipart upload", begin));
        }
        String uploadId = firstTagText(begin.body, "UploadId");
        if (uploadId == null) {
            throw new IOException("S3 accepted the multipart upload but did not return an "
                + "upload id; the response was: " + truncate(begin.body));
        }

        List<String> etags = new ArrayList<>();
        try {
            long offset = 0;
            int partNumber = 1;
            while (offset < size) {
                long length = Math.min(PART_SIZE, size - offset);
                byte[] part = readSlice(file, offset, (int) length);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("content-length", Long.toString(length));
                Response r = send("PUT",
                    uriFor(key, "partNumber=" + partNumber + "&uploadId="
                        + AwsV4Signer.uriEncode(uploadId, false)),
                    headers, HttpRequest.BodyPublishers.ofByteArray(part),
                    AwsV4Signer.hex(AwsV4Signer.sha256(part)),
                    HttpResponse.BodyHandlers.ofString());
                if (!r.ok()) {
                    throw new IOException(explain("upload part " + partNumber, r));
                }
                String etag = r.header("etag");
                if (etag == null) {
                    throw new IOException("part " + partNumber
                        + " was accepted without an ETag, so the upload cannot be completed");
                }
                etags.add(etag);
                offset += length;
                partNumber++;
            }

            StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
            for (int i = 0; i < etags.size(); i++) {
                xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                   .append(escapeXml(etags.get(i))).append("</ETag></Part>");
            }
            xml.append("</CompleteMultipartUpload>");
            byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-length", Long.toString(body.length));
            headers.put("content-type", "application/xml");
            Response done = send("POST",
                uriFor(key, "uploadId=" + AwsV4Signer.uriEncode(uploadId, false)),
                headers, HttpRequest.BodyPublishers.ofByteArray(body),
                AwsV4Signer.hex(AwsV4Signer.sha256(body)),
                HttpResponse.BodyHandlers.ofString());
            /*
             * CompleteMultipartUpload can answer 200 and still have failed: S3 holds the
             * connection open while it assembles the object and then writes an <Error> into
             * a body whose status line has already been sent. Reading only the status here
             * would report a backup as stored that does not exist.
             */
            if (!done.ok() || done.body.contains("<Error")) {
                throw new IOException(explain("complete the multipart upload", done));
            }
        } catch (IOException | RuntimeException e) {
            abortMultipart(key, uploadId, e);
            throw e;
        }
    }

    /**
     * Abandons a multipart upload, and never masks the failure that caused it.
     *
     * <p>Parts left behind by an abandoned upload do not appear in any listing and are
     * billed until a lifecycle rule removes them, so this matters more than its size
     * suggests. If the abort itself fails there is nothing useful to do about it, and
     * throwing here would replace the real error -- "the network went away mid-upload" --
     * with a second, less informative one.
     */
    private void abortMultipart(String key, String uploadId, Throwable cause) {
        try {
            send("DELETE", uriFor(key, "uploadId=" + AwsV4Signer.uriEncode(uploadId, false)),
                new LinkedHashMap<>(), HttpRequest.BodyPublishers.noBody(),
                AwsV4Signer.EMPTY_PAYLOAD_SHA256, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            cause.addSuppressed(new IOException("the incomplete multipart upload " + uploadId
                + " could not be aborted and its parts will be billed until a lifecycle "
                + "rule removes them", e));
        }
    }

    private Map<String, String> objectHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/zip");
        if (!storageClass.isEmpty()) {
            headers.put("x-amz-storage-class", storageClass);
        }
        if (!sse.isEmpty()) {
            headers.put("x-amz-server-side-encryption", sse);
            if ("aws:kms".equalsIgnoreCase(sse) && !kmsKeyId.isEmpty()) {
                headers.put("x-amz-server-side-encryption-aws-kms-key-id", kmsKeyId);
            }
        }
        return headers;
    }

    // ------------------------------------------------------------------
    // List, fetch, delete
    // ------------------------------------------------------------------

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String continuation = null;
        do {
            StringBuilder query = new StringBuilder("list-type=2&max-keys=1000");
            if (!prefix.isEmpty()) {
                query.append("&prefix=").append(AwsV4Signer.uriEncode(prefix + "/", false));
            }
            if (continuation != null) {
                query.append("&continuation-token=")
                     .append(AwsV4Signer.uriEncode(continuation, false));
            }
            Response r = send("GET", uriFor(null, query.toString()), new LinkedHashMap<>(),
                HttpRequest.BodyPublishers.noBody(), AwsV4Signer.EMPTY_PAYLOAD_SHA256,
                HttpResponse.BodyHandlers.ofString());
            if (!r.ok()) {
                throw new IOException(explain("list the bucket", r));
            }
            continuation = null;
            Element root = parseXml(r.body);
            if ("true".equalsIgnoreCase(text(root, "IsTruncated"))) {
                continuation = text(root, "NextContinuationToken");
            }
            NodeList contents = root.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element item = (Element) contents.item(i);
                String name = Destinations.nameUnder(prefix, text(item, "Key"));
                if (name == null || !Destinations.isArchiveName(name)) {
                    continue;
                }
                long size = 0;
                try {
                    size = Long.parseLong(text(item, "Size"));
                } catch (RuntimeException ignored) {
                    // A listing without a size is still a listing; the console shows a dash.
                }
                out.add(new RemoteFile(name, size, parseInstant(text(item, "LastModified"))));
            }
        } while (continuation != null && !continuation.isEmpty());

        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        String key = Destinations.join(prefix, Destinations.requireSimpleName(name));
        Response r = send("GET", uriFor(key, null), new LinkedHashMap<>(),
            HttpRequest.BodyPublishers.noBody(), AwsV4Signer.EMPTY_PAYLOAD_SHA256,
            HttpResponse.BodyHandlers.ofFile(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING));
        if (!r.ok()) {
            // The body went to the file, so there is nothing quotable; the status carries it.
            throw new IOException("could not download " + name + " from S3: HTTP " + r.status);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        String key = Destinations.join(prefix, Destinations.requireSimpleName(name));
        Response r = send("DELETE", uriFor(key, null), new LinkedHashMap<>(),
            HttpRequest.BodyPublishers.noBody(), AwsV4Signer.EMPTY_PAYLOAD_SHA256,
            HttpResponse.BodyHandlers.ofString());
        // 404 is success here: S3 deletes are idempotent and retention asking twice is not
        // an error.
        if (!r.ok() && r.status != 404) {
            throw new IOException(explain("delete " + name, r));
        }
    }

    @Override
    public String probe() throws IOException {
        String key = Destinations.join(prefix, ".oie-backup-probe");
        byte[] body = "probe\n".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = objectHeaders();
        headers.put("content-length", Long.toString(body.length));
        Response put = send("PUT", uriFor(key, null), headers,
            HttpRequest.BodyPublishers.ofByteArray(body),
            AwsV4Signer.hex(AwsV4Signer.sha256(body)), HttpResponse.BodyHandlers.ofString());
        if (!put.ok()) {
            throw new IOException(explain("write to the bucket", put));
        }

        int found = list().size();

        Response del = send("DELETE", uriFor(key, null), new LinkedHashMap<>(),
            HttpRequest.BodyPublishers.noBody(), AwsV4Signer.EMPTY_PAYLOAD_SHA256,
            HttpResponse.BodyHandlers.ofString());
        String where = bucket + (prefix.isEmpty() ? "" : "/" + prefix);
        if (!del.ok() && del.status != 404) {
            return "Wrote a probe object to " + where + " and listed " + found
                + " archive(s), but could not delete the probe (HTTP " + del.status
                + "). Retention will not be able to remove old archives from here.";
        }
        return "Wrote, listed and deleted a probe object in " + where + ". "
            + found + " archive(s) already there."
            + (sse.isEmpty() ? " Server-side encryption is not requested." : "");
    }

    @Override
    public void close() {
        // HttpClient has no close in this JDK baseline; it is garbage collected with us.
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * The URI for a key, in whichever addressing style is configured.
     *
     * @param key   the full object key, or null for a bucket-level request
     * @param query the raw query string, already encoded, or null
     */
    private URI uriFor(String key, String query) {
        StringBuilder url = new StringBuilder();
        url.append(endpoint.getScheme()).append("://");
        if (pathStyle) {
            url.append(endpoint.getAuthority()).append('/').append(bucket);
        } else {
            url.append(bucket).append('.').append(endpoint.getAuthority());
        }
        if (endpoint.getPath() != null && !endpoint.getPath().isEmpty()
            && !"/".equals(endpoint.getPath())) {
            url.append(endpoint.getPath());
        }
        url.append('/');
        if (key != null) {
            url.append(AwsV4Signer.uriEncode(key, true));
        }
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        return URI.create(url.toString());
    }

    private static final class Response {
        final int status;
        final String body;
        final HttpHeadersView headers;

        Response(int status, String body, HttpHeadersView headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String header(String name) {
            return headers.first(name);
        }
    }

    /** A thin view so {@link Response} does not carry a JDK type into the rest of the file. */
    private static final class HttpHeadersView {
        private final java.net.http.HttpHeaders headers;

        HttpHeadersView(java.net.http.HttpHeaders headers) {
            this.headers = headers;
        }

        String first(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private <T> Response send(String method, URI uri, Map<String, String> headers,
                              HttpRequest.BodyPublisher body, String payloadSha256,
                              HttpResponse.BodyHandler<T> handler) throws IOException {
        Map<String, String> signed = AwsV4Signer.sign(method, uri, region, "s3", headers,
            payloadSha256, accessKeyId, secretAccessKey, sessionToken);

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
            .method(method, body);
        for (Map.Entry<String, String> e : signed.entrySet()) {
            // The JDK sets these itself and rejects an attempt to set them again; they are
            // signed all the same, which is what matters.
            if ("host".equalsIgnoreCase(e.getKey())
                || "content-length".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            b.header(e.getKey(), e.getValue());
        }

        try {
            HttpResponse<T> response = http.send(b.build(), handler);
            Object value = response.body();
            return new Response(response.statusCode(),
                value instanceof String ? (String) value : "",
                new HttpHeadersView(response.headers()));
        } catch (InterruptedException e) {
            // Restored so that a shutdown interrupting an upload still stops the scheduler
            // rather than being swallowed here and having to be noticed again later.
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while talking to " + uri.getHost(), e);
        }
    }

    /**
     * An S3 error turned into a sentence.
     *
     * <p>S3's error bodies carry a {@code Code} and a {@code Message} that name the actual
     * problem -- {@code SignatureDoesNotMatch}, {@code NoSuchBucket},
     * {@code AccessDenied} -- and hiding them behind "HTTP 403" is how an afternoon gets
     * spent on the wrong theory. They contain no credential; the request id they carry is
     * what support asks for.
     */
    private String explain(String what, Response r) {
        String code = firstTagText(r.body, "Code");
        String message = firstTagText(r.body, "Message");
        StringBuilder sb = new StringBuilder("could not ").append(what)
            .append(": HTTP ").append(r.status);
        if (code != null) {
            sb.append(" ").append(code);
        }
        if (message != null) {
            sb.append(" -- ").append(message);
        }
        if (code == null && message == null && r.body != null && !r.body.isBlank()) {
            sb.append(" -- ").append(truncate(r.body));
        }
        if (r.status == 403 && "SignatureDoesNotMatch".equals(code)) {
            sb.append(". Check the secret access key, and the region: a bucket signed for "
                + "the wrong region fails exactly like a wrong key.");
        }
        if (r.status == 301 || "PermanentRedirect".equals(code)) {
            sb.append(". The bucket is in another region, or needs path-style addressing.");
        }
        return sb.toString();
    }

    private static String truncate(String body) {
        String b = body == null ? "" : body.strip();
        return b.length() > 400 ? b.substring(0, 400) + "..." : b;
    }

    private static String escapeXml(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The file's SHA-256, streamed.
     *
     * <p>Streamed rather than read into memory because the file being hashed is the backup,
     * and reading a multi-gigabyte archive into the engine's heap to hash it would take the
     * engine down to protect its data.
     */
    private static String sha256OfFile(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, digest)) {
            while (digesting.read(buffer) >= 0) {
                // Reading is the point; the digest is updated as a side effect.
            }
        }
        return AwsV4Signer.hex(digest.digest());
    }

    private static byte[] readSlice(Path file, long offset, int length) throws IOException {
        byte[] buffer = new byte[length];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buffer);
        }
        return buffer;
    }

    // ------------------------------------------------------------------
    // XML, without a parser dependency
    // ------------------------------------------------------------------

    private static Element parseXml(String body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // S3 never sends a doctype, and accepting one would make a hostile or
            // compromised endpoint's response able to read files off this engine.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        } catch (Exception e) {
            throw new IOException("the storage endpoint answered with something that is not "
                + "an S3 listing: " + truncate(body), e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    /**
     * The text of the first {@code <tag>} in a body, by string scan.
     *
     * <p>Used for the few single values read out of error and multipart responses. A full
     * parse would be tidier, but this is also called on bodies that may not be XML at all --
     * a proxy's HTML error page, most often -- and returning null from a scan is a better
     * outcome there than an exception from a parser while already handling an error.
     */
    private static String firstTagText(String body, String tag) {
        if (body == null) {
            return null;
        }
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = body.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = body.indexOf(close, start + open.length());
        return end < 0 ? null : body.substring(start + open.length(), end).trim();
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Lowercases a storage class the way the header wants it, for the settings form. */
    static String normaliseStorageClass(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
