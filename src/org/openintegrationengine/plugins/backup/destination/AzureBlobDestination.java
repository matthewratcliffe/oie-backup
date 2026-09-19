/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup.destination;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Azure Blob Storage, by account key or by shared access signature.
 *
 * <p>Two ways in, and they are not equivalent. A <b>SAS token</b> can be scoped to one
 * container, given create and write but not delete, and given an expiry, so it is the one
 * to prefer for a backup: the worst a leaked one does is let somebody write into a
 * container for a while. An <b>account key</b> is every container in the account, read and
 * write and delete, and it does not expire -- the plugin encrypts it before storing it, the
 * API never returns it, and it is still the bigger thing to lose. The console says this
 * beside the field rather than leaving the choice to look like a formatting preference.
 *
 * <p>Written against the REST API and the JDK's {@link HttpClient} for the same reason as
 * the S3 destination: the engine ships no Azure SDK at all, and bundling one -- Azure's
 * blob client pulls in Reactor, Netty and a dozen more jars -- would put more third-party
 * code inside the engine's JVM than the rest of this repository's extensions combined.
 *
 * <h2>Large archives</h2>
 *
 * <p>A single {@code Put Blob} is capped at 5000 MiB, which is plenty, but a single request
 * that large is also a single failure: losing the connection at 4 GiB means starting again.
 * So anything past {@link #BLOCK_THRESHOLD} is uploaded as blocks and committed with a
 * block list, which is Azure's equivalent of a multipart upload. Uncommitted blocks expire
 * on their own after a week, so an abandoned upload cleans itself up -- the one way this is
 * kinder than S3.
 */
final class AzureBlobDestination implements Destination {

    /** The REST API version this speaks. Sent on every request; Azure requires it. */
    private static final String API_VERSION = "2021-08-06";

    private static final long BLOCK_THRESHOLD = 256L * 1024 * 1024;
    private static final long BLOCK_SIZE = 64L * 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private static final DateTimeFormatter RFC1123 =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final HttpClient http;
    private final String account;
    private final String container;
    private final String prefix;
    private final String endpoint;
    private final byte[] accountKey;
    /** Without its leading {@code ?}. Empty when authenticating with the account key. */
    private final String sasToken;

    AzureBlobDestination(DestinationConfig config) {
        this.account = config.require(Destinations.K_ACCOUNT, "The storage account");
        this.container = config.require(Destinations.K_CONTAINER, "The container");
        this.prefix = Destinations.normalisePrefix(config.get(Destinations.K_PREFIX, ""));

        String configured = config.get(Destinations.K_ENDPOINT, "");
        String base = configured.isEmpty()
            ? "https://" + account + ".blob.core.windows.net"
            : (configured.contains("://") ? configured : "https://" + configured);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base;

        String auth = config.get(Destinations.K_AZURE_AUTH, "key");
        if ("sas".equalsIgnoreCase(auth)) {
            String token = config.require(Destinations.K_SAS, "The SAS token");
            this.sasToken = token.startsWith("?") ? token.substring(1) : token;
            this.accountKey = null;
        } else {
            String key = config.require(Destinations.K_SECRET, "The account key");
            try {
                this.accountKey = Base64.getDecoder().decode(key.trim());
            } catch (IllegalArgumentException e) {
                // Worth naming: an account key pasted with a character missing fails here
                // rather than at the first request, where it would look like a 403.
                throw new IllegalArgumentException(
                    "the account key is not valid base64; copy it from the portal's "
                    + "Access keys page in full");
            }
            this.sasToken = "";
        }

        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public String describe() {
        return "Azure Blob: " + account + "/" + container
            + (prefix.isEmpty() ? "" : "/" + prefix)
            + (sasToken.isEmpty() ? " (account key)" : " (SAS)");
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    @Override
    public void put(String name, Path file) throws IOException {
        String blob = Destinations.join(prefix, Destinations.requireSimpleName(name));
        long size = Files.size(file);
        if (size > BLOCK_THRESHOLD) {
            blockPut(blob, file, size);
        } else {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-ms-blob-type", "BlockBlob");
            headers.put("Content-Type", "application/zip");
            Response r = send("PUT", blob, null, headers, size,
                HttpRequest.BodyPublishers.ofFile(file), HttpResponse.BodyHandlers.ofString());
            if (!r.ok()) {
                throw new IOException(explain("upload", r));
            }
        }
    }

    private void blockPut(String blob, Path file, long size) throws IOException {
        List<String> blockIds = new ArrayList<>();
        long offset = 0;
        int index = 0;
        while (offset < size) {
            int length = (int) Math.min(BLOCK_SIZE, size - offset);
            byte[] block = readSlice(file, offset, length);
            /*
             * Block ids must be the same length as each other and base64, which is why this
             * is a zero-padded counter rather than the obvious Integer.toString: "9" and
             * "10" base64 to different lengths and Azure rejects the block list with an
             * error that does not mention length.
             */
            String id = Base64.getEncoder().encodeToString(
                String.format(Locale.ROOT, "%08d", index).getBytes(StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            Response r = send("PUT", blob,
                "comp=block&blockid=" + urlEncode(id), headers, length,
                HttpRequest.BodyPublishers.ofByteArray(block),
                HttpResponse.BodyHandlers.ofString());
            if (!r.ok()) {
                throw new IOException(explain("upload block " + index, r));
            }
            blockIds.add(id);
            offset += length;
            index++;
        }

        StringBuilder xml = new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><BlockList>");
        for (String id : blockIds) {
            xml.append("<Latest>").append(id).append("</Latest>");
        }
        xml.append("</BlockList>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/xml");
        headers.put("x-ms-blob-content-type", "application/zip");
        Response r = send("PUT", blob, "comp=blocklist", headers, body.length,
            HttpRequest.BodyPublishers.ofByteArray(body), HttpResponse.BodyHandlers.ofString());
        if (!r.ok()) {
            throw new IOException(explain("commit the block list", r));
        }
    }

    // ------------------------------------------------------------------
    // List, fetch, delete
    // ------------------------------------------------------------------

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String marker = "";
        do {
            StringBuilder query = new StringBuilder("restype=container&comp=list&maxresults=1000");
            if (!prefix.isEmpty()) {
                query.append("&prefix=").append(urlEncode(prefix + "/"));
            }
            if (!marker.isEmpty()) {
                query.append("&marker=").append(urlEncode(marker));
            }
            Response r = send("GET", null, query.toString(), new LinkedHashMap<>(), 0,
                HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofString());
            if (!r.ok()) {
                throw new IOException(explain("list the container", r));
            }
            Element root = parseXml(r.body);
            marker = orEmpty(text(root, "NextMarker"));

            NodeList blobs = root.getElementsByTagName("Blob");
            for (int i = 0; i < blobs.getLength(); i++) {
                Element item = (Element) blobs.item(i);
                String name = Destinations.nameUnder(prefix, text(item, "Name"));
                if (name == null || !Destinations.isArchiveName(name)) {
                    continue;
                }
                long size = 0;
                Instant modified = null;
                NodeList props = item.getElementsByTagName("Properties");
                if (props.getLength() > 0) {
                    Element p = (Element) props.item(0);
                    try {
                        size = Long.parseLong(orEmpty(text(p, "Content-Length")));
                    } catch (RuntimeException ignored) {
                        // A listing without a length is still a listing.
                    }
                    modified = parseRfc1123(text(p, "Last-Modified"));
                }
                out.add(new RemoteFile(name, size, modified));
            }
        } while (!marker.isEmpty());

        out.sort(Comparator.comparing((RemoteFile f) -> f.name).reversed());
        return out;
    }

    @Override
    public void fetch(String name, Path target) throws IOException {
        String blob = Destinations.join(prefix, Destinations.requireSimpleName(name));
        Response r = send("GET", blob, null, new LinkedHashMap<>(), 0,
            HttpRequest.BodyPublishers.noBody(),
            HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (!r.ok()) {
            throw new IOException("could not download " + name + " from Azure: HTTP " + r.status);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        String blob = Destinations.join(prefix, Destinations.requireSimpleName(name));
        Response r = send("DELETE", blob, null, new LinkedHashMap<>(), 0,
            HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofString());
        if (!r.ok() && r.status != 404) {
            throw new IOException(explain("delete " + name, r));
        }
    }

    @Override
    public String probe() throws IOException {
        String blob = Destinations.join(prefix, ".oie-backup-probe");
        byte[] body = "probe\n".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ms-blob-type", "BlockBlob");
        headers.put("Content-Type", "text/plain");
        Response put = send("PUT", blob, null, headers, body.length,
            HttpRequest.BodyPublishers.ofByteArray(body), HttpResponse.BodyHandlers.ofString());
        if (!put.ok()) {
            throw new IOException(explain("write to the container", put));
        }

        int found = list().size();

        Response del = send("DELETE", blob, null, new LinkedHashMap<>(), 0,
            HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofString());
        String where = account + "/" + container + (prefix.isEmpty() ? "" : "/" + prefix);
        if (!del.ok() && del.status != 404) {
            return "Wrote a probe blob to " + where + " and listed " + found
                + " archive(s), but could not delete the probe (HTTP " + del.status
                + "). Retention will not be able to remove old archives from here"
                + (sasToken.isEmpty() ? "." : "; a SAS without the delete permission does "
                    + "exactly this, which may be deliberate.");
        }
        return "Wrote, listed and deleted a probe blob in " + where + ". "
            + found + " archive(s) already there.";
    }

    @Override
    public void close() {
        // Nothing held open.
    }

    // ------------------------------------------------------------------
    // Signing
    // ------------------------------------------------------------------

    private <T> Response send(String method, String blob, String query,
                              Map<String, String> headers, long contentLength,
                              HttpRequest.BodyPublisher body,
                              HttpResponse.BodyHandler<T> handler) throws IOException {
        String path = "/" + container + (blob == null ? "" : "/" + encodePath(blob));
        String effectiveQuery = query == null ? "" : query;
        if (!sasToken.isEmpty()) {
            effectiveQuery = effectiveQuery.isEmpty() ? sasToken
                : effectiveQuery + "&" + sasToken;
        }
        URI uri = URI.create(endpoint + path
            + (effectiveQuery.isEmpty() ? "" : "?" + effectiveQuery));

        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("x-ms-version", API_VERSION);
        all.put("x-ms-date", RFC1123.format(ZonedDateTime.now(java.time.ZoneOffset.UTC)));

        if (accountKey != null) {
            all.put("Authorization", "SharedKey " + account + ":"
                + sharedKeySignature(method, path, query, all, contentLength));
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
            .method(method, body);
        for (Map.Entry<String, String> e : all.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }

        try {
            HttpResponse<T> response = http.send(b.build(), handler);
            Object value = response.body();
            return new Response(response.statusCode(),
                value instanceof String ? (String) value : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while talking to " + uri.getHost(), e);
        }
    }

    /**
     * Blob service Shared Key signing.
     *
     * <p>The string to sign is thirteen fixed header slots -- most of which this plugin
     * never sets and which therefore have to be present and empty -- followed by the
     * canonicalised {@code x-ms-*} headers and the canonicalised resource. Each blank line
     * is load-bearing: dropping one shifts everything below it and produces a 403 whose
     * message is "the MAC signature found in the HTTP request is not the same as any
     * computed signature", which says nothing about which line.
     *
     * <p>{@code Content-Length} is the one slot with a rule rather than a value: since API
     * version 2015-02-21 a length of zero is signed as the empty string, not as {@code 0}.
     */
    private String sharedKeySignature(String method, String path, String query,
                                      Map<String, String> headers, long contentLength) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append('\n');
        sb.append(orEmpty(headers.get("Content-Encoding"))).append('\n');
        sb.append(orEmpty(headers.get("Content-Language"))).append('\n');
        sb.append(contentLength == 0 ? "" : Long.toString(contentLength)).append('\n');
        sb.append(orEmpty(headers.get("Content-MD5"))).append('\n');
        sb.append(orEmpty(headers.get("Content-Type"))).append('\n');
        // Date: empty, because x-ms-date is used instead and signing both is an error.
        sb.append('\n');
        sb.append(orEmpty(headers.get("If-Modified-Since"))).append('\n');
        sb.append(orEmpty(headers.get("If-Match"))).append('\n');
        sb.append(orEmpty(headers.get("If-None-Match"))).append('\n');
        sb.append(orEmpty(headers.get("If-Unmodified-Since"))).append('\n');
        sb.append(orEmpty(headers.get("Range"))).append('\n');

        TreeMap<String, String> msHeaders = new TreeMap<>();
        headers.forEach((k, v) -> {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-ms-")) {
                msHeaders.put(lower, v == null ? "" : v.trim());
            }
        });
        msHeaders.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));

        sb.append('/').append(account).append(path);
        if (query != null && !query.isEmpty()) {
            TreeMap<String, List<String>> params = new TreeMap<>();
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
                String value = eq < 0 ? ""
                    : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            params.forEach((name, values) -> {
                java.util.Collections.sort(values);
                sb.append('\n').append(name).append(':').append(String.join(",", values));
            });
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accountKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * An Azure error turned into a sentence.
     *
     * <p>Azure puts the useful part in an {@code x-ms-error-code} header and repeats it in
     * the body, and the two most common answers here -- {@code AuthenticationFailed} and
     * {@code ContainerNotFound} -- are indistinguishable from each other as bare status
     * codes.
     */
    private String explain(String what, Response r) {
        String code = firstTagText(r.body, "Code");
        String message = firstTagText(r.body, "Message");
        StringBuilder sb = new StringBuilder("could not ").append(what)
            .append(": HTTP ").append(r.status);
        if (code != null) {
            sb.append(' ').append(code);
        }
        if (message != null) {
            // Azure's Message carries a newline and a request id; the first line is the part
            // that means anything to a person.
            sb.append(" -- ").append(message.split("\n")[0].trim());
        }
        if (r.status == 403 && sasToken.isEmpty()) {
            sb.append(". Check the account key, and that the account name matches it.");
        }
        if (r.status == 403 && !sasToken.isEmpty()) {
            sb.append(". Check the SAS has not expired and carries the permissions this "
                + "needs: create and write to back up, read to restore, list for the "
                + "archive list, delete for retention.");
        }
        return sb.toString();
    }

    private static String encodePath(String blob) {
        StringBuilder sb = new StringBuilder();
        String[] segments = blob.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(urlEncode(segments[i]));
        }
        return sb.toString();
    }

    /** Percent-encoding that leaves the unreserved set alone, unlike URLEncoder's {@code +}. */
    private static String urlEncode(String value) {
        return AwsV4Signer.uriEncode(value, false);
    }

    private static byte[] readSlice(Path file, long offset, int length) throws IOException {
        byte[] buffer = new byte[length];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buffer);
        }
        return buffer;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static Element parseXml(String body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        } catch (Exception e) {
            String b = body == null ? "" : body.strip();
            throw new IOException("the endpoint answered with something that is not a blob "
                + "listing: " + (b.length() > 400 ? b.substring(0, 400) + "..." : b), e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

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

    private static Instant parseRfc1123(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
