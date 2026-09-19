/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link BackupService} onto HTTP.
 *
 * <p>Thin on purpose: every rule worth having lives in the service and the destinations, so
 * that calling the API directly is subject to the same refusals as clicking the button. What
 * this class does add is the shape of the answer -- a failed operation is {@code 200} with
 * {@code ok:false} and a sentence, not a 500 carrying a serialised Java exception, because
 * the console renders what it is given and a stack trace in a web page is an error message
 * wrapped in something that makes people stop reading.
 */
public class BackupServlet extends MirthServlet implements BackupServletInterface {

    public BackupServlet(@Context HttpServletRequest request,
                         @Context SecurityContext securityContext) {
        super(request, securityContext, BackupServicePlugin.PLUGIN_POINT_NAME);
    }

    private static BackupService service() {
        BackupService s = BackupServicePlugin.service();
        if (s == null) {
            // Installed, but the ServicePlugin never started: a server fault, not a bad
            // request, so it gets a status that says so.
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return s;
    }

    private static Map<String, Object> failure(Throwable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        out.put("error", BackupService.message(t));
        return out;
    }

    private static String option(Map<String, String> options, String key, String fallback) {
        if (options == null) {
            return fallback;
        }
        String v = options.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static boolean flag(Map<String, String> options, String key) {
        return "true".equalsIgnoreCase(option(options, key, "false"));
    }

    @Override
    public Map<String, Object> getStatus() {
        try {
            Map<String, Object> out = service().status();
            out.put("uploads", service().uploadedArchives());
            return out;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> setSettings(Map<String, String> settings) {
        try {
            return service().saveSettings(settings);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> runNow() {
        try {
            // respectClaim=false: somebody pressing the button has asked for a backup of
            // this engine, and being told "another node is doing the scheduled one" is a
            // useful answer but not a reason to refuse. The local lock still applies, so
            // this node never runs two at once.
            return service().runBackup("manual", false);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> archives() {
        try {
            return service().archives();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> probe() {
        try {
            return service().probe();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> inspect(Map<String, String> options) {
        try {
            String name = option(options, "name", "");
            if (name.isEmpty()) {
                return named("an archive name is required");
            }
            return service().inspect(name, option(options, "passphrase", ""));
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> restore(Map<String, String> options) {
        try {
            String name = option(options, "name", "");
            if (name.isEmpty()) {
                return named("an archive name is required");
            }
            /*
             * scope and channels are passed through as null when the caller did not send
             * them at all, and verbatim otherwise -- including empty. The service reads
             * null as "everything this archive holds" and an empty list as "nothing", and
             * collapsing the two here would make it impossible for the console to say
             * "restore no configuration, only the messages". See BackupService.restore.
             */
            return service().restore(name,
                options == null ? null : options.get("scope"),
                options == null ? null : options.get("channels"),
                option(options, "passphrase", ""),
                flag(options, "safetyBackup"),
                flag(options, "redeploy"),
                flag(options, "deployForMessages"),
                flag(options, "replace"),
                // Never removed by a replacing restore: see ConfigCapture.findRemovals.
                actingUser());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> deleteArchive(String name) {
        try {
            return service().deleteArchive(name);
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * Streams an archive to the browser.
     *
     * <p>Streamed rather than read into a byte array: an archive does not fit in a response
     * the engine assembles in memory, and the one time this is used is the one time the
     * engine can least afford to fall over.
     *
     * <p>A missing or unacceptable name answers {@code 404} rather than {@code 400}. The
     * difference matters here: this endpoint resolves a name against one directory, and
     * distinguishing "not a name we would accept" from "we accept it but it is not there"
     * would tell a caller which names exist.
     */
    @Override
    public Response download(String name) {
        Path file;
        try {
            file = service().downloadable(name);
        } catch (Exception e) {
            throw new MirthApiException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        if (file == null) {
            throw new MirthApiException(Response.Status.NOT_FOUND);
        }

        final Path source = file;
        StreamingOutput body = (OutputStream out) -> {
            try (InputStream in = Files.newInputStream(source)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
            }
        };

        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            size = -1;
        }

        Response.ResponseBuilder response = Response.ok(body)
            .type(javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM)
            // The name is already constrained to a plain archive file name by
            // downloadable(), so it cannot carry a quote or a newline into this header.
            .header("Content-Disposition",
                "attachment; filename=\"" + source.getFileName() + "\"");
        if (size >= 0) {
            response.header("Content-Length", size);
        }
        return response.build();
    }

    @Override
    public Map<String, Object> upload(String name, InputStream body) {
        try {
            if (body == null) {
                return named("no archive was uploaded");
            }
            return service().acceptUpload(name, body);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> uploads() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("uploads", service().uploadedArchives());
            return out;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> deleteUpload(String name) {
        try {
            return service().deleteUpload(name);
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * The username behind this request, or null.
     *
     * <p>Used for one thing: a replacing restore must not remove the account performing it.
     * Taken from the security context rather than from anything the caller sends, because a
     * caller naming somebody else's account would be choosing which account survives.
     */
    private String actingUser() {
        try {
            return sc != null && sc.getUserPrincipal() != null
                ? sc.getUserPrincipal().getName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> previewRemovals(Map<String, String> options) {
        try {
            String name = option(options, "name", "");
            if (name.isEmpty()) {
                return named("an archive name is required");
            }
            return service().previewRemovals(name,
                options == null ? null : options.get("scope"),
                option(options, "passphrase", ""),
                actingUser());
        } catch (Exception e) {
            return failure(e);
        }
    }

    private static Map<String, Object> named(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        out.put("error", reason);
        return out;
    }
}
