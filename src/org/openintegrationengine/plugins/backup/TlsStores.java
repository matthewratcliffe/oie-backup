/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * The TLS Manager extension's two keystores, as this plugin sees them.
 *
 * <p>In its default database mode TLS Manager keeps each store as a base64 PKCS12 document in
 * the engine's configuration properties, under the plugin point {@value #PLUGIN}, with an
 * empty store password. That is the whole interface between the two extensions: this plugin
 * reads and writes those two properties, and TLS Manager notices the change and reloads.
 * Nothing here links against TLS Manager, so either can be installed without the other.
 *
 * <p>A TLS Manager running in filesystem mode has no such properties, so nothing is captured
 * for it. Its keystores are files the operator already manages.
 *
 * <p>Restore merges by alias. An alias in the archive replaces the same alias on the engine
 * and an alias only the engine has is left alone. It never deletes, because a private key that
 * was not in the archive cannot be recovered once removed.
 */
final class TlsStores {

    /** The plugin point TLS Manager saves its properties under. */
    static final String PLUGIN = "TLS Manager";

    /** Property names of the key pair store and the trusted certificate store. */
    static final List<String> STORES = List.of("extraKeyStore", "extraTrustStore");

    /** Directory under {@code config/} that holds the two files. */
    static final String FILE_DIR = "tls-manager";

    private static final String TYPE = "PKCS12";
    private static final char[] PASSWORD = new char[0];

    private TlsStores() {
    }

    /** How many aliases a base64 PKCS12 store holds. */
    static int aliasCount(String b64) throws Exception {
        return Collections.list(load(b64).aliases()).size();
    }

    /**
     * The engine's store with the archive's entries added over it.
     *
     * @param existing the engine's current base64 store, or null if it has none
     * @param incoming the archive's base64 store
     * @return the merged store, base64
     */
    static String merge(String existing, String incoming) throws Exception {
        KeyStore target = existing == null || existing.isBlank() ? empty() : load(existing);
        KeyStore source = load(incoming);
        for (String alias : Collections.list(source.aliases())) {
            if (source.isCertificateEntry(alias)) {
                target.setCertificateEntry(alias, source.getCertificate(alias));
            } else {
                KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(PASSWORD);
                target.setEntry(alias, source.getEntry(alias, protection), protection);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        target.store(out, PASSWORD);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static KeyStore empty() throws Exception {
        KeyStore store = KeyStore.getInstance(TYPE);
        store.load(null, PASSWORD);
        return store;
    }

    private static KeyStore load(String b64) throws Exception {
        KeyStore store = KeyStore.getInstance(TYPE);
        store.load(new ByteArrayInputStream(Base64.getMimeDecoder().decode(b64)), PASSWORD);
        return store;
    }

    /** Build-time check, in the same shape as the other classes' {@code selfTest}. */
    @SuppressWarnings("unused")
    private static String selfTest() throws Exception {
        KeyStore engine = empty();
        KeyStore archive = empty();
        KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(PASSWORD);
        javax.crypto.SecretKey a = new javax.crypto.spec.SecretKeySpec(new byte[16], "AES");
        javax.crypto.SecretKey b = new javax.crypto.spec.SecretKeySpec(new byte[] {1, 2, 3, 4,
            5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, "AES");
        engine.setEntry("keep", new KeyStore.SecretKeyEntry(a), protection);
        engine.setEntry("both", new KeyStore.SecretKeyEntry(a), protection);
        archive.setEntry("both", new KeyStore.SecretKeyEntry(b), protection);
        archive.setEntry("new", new KeyStore.SecretKeyEntry(b), protection);

        KeyStore merged = load(merge(encode(engine), encode(archive)));
        if (Collections.list(merged.aliases()).size() != 3) {
            return "a merge should hold keep, both and new, not "
                + Collections.list(merged.aliases());
        }
        if (!java.util.Arrays.equals(
            ((KeyStore.SecretKeyEntry) merged.getEntry("both", protection)).getSecretKey()
                .getEncoded(), b.getEncoded())) {
            return "the archive's entry did not replace the engine's under the same alias";
        }
        if (!java.util.Arrays.equals(
            ((KeyStore.SecretKeyEntry) merged.getEntry("keep", protection)).getSecretKey()
                .getEncoded(), a.getEncoded())) {
            return "an alias only the engine has was changed";
        }
        if (aliasCount(merge(null, encode(archive))) != 2) {
            return "merging into an engine with no store should give the archive's entries";
        }
        return null;
    }

    private static String encode(KeyStore store) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, PASSWORD);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
