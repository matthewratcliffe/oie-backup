/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.InputStream;
import java.util.Map;

/**
 * REST surface for the Backup extension, served at {@code /api/backup}.
 *
 * <p>Everything the console needs, and every rule that has to hold whether or not the
 * console is what is calling: the name check that stops a restore reading an arbitrary
 * file, the refusal to delete something this plugin did not write, the passphrase
 * requirement, and the one-operation-at-a-time lock all live behind these endpoints rather
 * than in the page.
 *
 * <p>Responses are plain {@code Map}s of primitives and lists of tab-separated strings.
 * The engine's XStream serialiser needs every custom type allow-listed, and a map of
 * primitives keeps a serialisation concern out of what is otherwise a thin API. See
 * {@link Tsv} for why the rows are not nested objects.
 *
 * <p>A failed operation answers {@code 200} with {@code ok:false} and a sentence, not a 500
 * carrying a serialised Java exception -- the same as the other extensions here. A push
 * the remote refused, a passphrase that does not match, a bucket that does not exist: these
 * are answers, not server faults.
 */
@Path("/backup")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface BackupServletInterface extends BaseServletInterface {

    /**
     * Settings, schedule, next run, history, and what is happening right now.
     *
     * <p>Deliberately cheap and deliberately offline: it reads the {@code configuration}
     * table and the channel list and never touches the destination. The console polls this
     * while a backup runs, and a status call that opened an SFTP session would make the
     * page as slow as the remote storage on its worst day.
     */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Backup settings, schedule, history and the state of any run in progress.")
    @MirthOperation(name = "backupStatus", display = "Get backup status", auditable = false)
    Map<String, Object> getStatus() throws ClientException;

    /**
     * Updates settings. Only the keys supplied are written.
     *
     * <p>A secret sent empty is left as it was, so saving the form without retyping the S3
     * key does not wipe it. To clear one, send the sentinel in
     * {@link BackupSettings#CLEAR_SECRET}.
     *
     * <p>The body must be XStream-shaped ({@code <map><entry><string>k</string>
     * <string>v</string></entry></map>}); a plain JSON object is refused by the engine's
     * deserialiser with a 500.
     */
    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update the backup settings.")
    @MirthOperation(name = "backupSetSettings", display = "Set backup settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param(value = "settings", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description =
            "The settings to store. Unsupplied keys are left as they were.", required = true)
        Map<String, String> settings) throws ClientException;

    /**
     * Takes a backup now, with the saved settings.
     *
     * <p>Synchronous, and it can run for hours on an engine with a large message history.
     * The console starts it and then polls {@code /status}, which reports the phase --
     * there is no separate job id, because there is only ever one run.
     */
    @POST
    @Path("/run")
    @io.swagger.v3.oas.annotations.Operation(summary = "Take a backup now.")
    @MirthOperation(name = "backupRunNow", display = "Run a backup now",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> runNow() throws ClientException;

    /**
     * What the configured destination holds, and what retention would remove from it.
     *
     * <p>This one does reach the destination, which is why it is not part of
     * {@code /status}.
     */
    @GET
    @Path("/archives")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "List the archives in the configured destination.")
    @MirthOperation(name = "backupArchives", display = "List backup archives",
        auditable = false)
    Map<String, Object> archives() throws ClientException;

    /**
     * Writes a marker to the destination, lists it and removes it again.
     *
     * <p>Exists so that a destination is known to work before a scheduled run tries it at
     * three in the morning. A destination that can be written to but not deleted from --
     * write-once storage, a SAS without delete -- passes with a note saying retention will
     * not work there.
     */
    @POST
    @Path("/probe")
    @io.swagger.v3.oas.annotations.Operation(summary = "Test the configured destination.")
    @MirthOperation(name = "backupProbe", display = "Test the backup destination",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> probe() throws ClientException;

    /**
     * Reads an archive's manifest without restoring anything.
     *
     * <p>Fetches the whole archive to do it, because that is what reading a file on remote
     * storage costs. Worth it: choosing between six backups by filename alone is how the
     * wrong one gets restored.
     */
    @POST
    @Path("/inspect")
    @io.swagger.v3.oas.annotations.Operation(summary = "Read an archive's manifest.")
    @MirthOperation(name = "backupInspect", display = "Inspect a backup archive",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> inspect(
        @Param(value = "options", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description =
            "name, and passphrase for an encrypted archive.", required = true)
        Map<String, String> options) throws ClientException;

    /**
     * Restores an archive onto this engine.
     *
     * <p>The destructive one. It overwrites configuration this engine currently holds, and
     * it is sequenced so that configuration lands before messages -- a message cannot be
     * imported into a channel that does not exist yet.
     *
     * <p>Options: {@code name}; {@code scope}; {@code channels}; {@code passphrase};
     * {@code safetyBackup}; {@code redeploy}; {@code deployForMessages}; {@code replace}.
     *
     * <p>{@code scope} and {@code channels} each distinguish three cases, and the
     * distinction is load-bearing. <b>Omitted</b> means everything of that kind the archive
     * holds, which is the convenient default for a scripted restore. The literal
     * {@code none} means none of that kind. <b>A comma-separated list</b> means exactly
     * those, and is taken literally -- an empty list restores nothing, never everything,
     * because an empty string is what a form posts when every box has been unticked and
     * reading that as "all of them" would overwrite this engine while claiming to have
     * honoured a selection.
     *
     * <p>So restoring only the channels out of an archive that also holds messages is
     * {@code scope=channels} and {@code channels=none}.
     *
     * <p>{@code replace} decides what happens to objects the archive does <b>not</b>
     * describe, within the selected scopes:
     *
     * <ul>
     *   <li><b>off</b> (the default) -- they are left exactly as they are, so the restore
     *       adds back what was lost rather than rolling the engine back to a moment in
     *       time;
     *   <li><b>on</b> -- they are removed, so the engine ends up matching the archive.
     *       This <b>deletes channels and their message history</b>, and there is no undo.
     * </ul>
     *
     * <p>Off is the default for an omitted parameter deliberately: a parameter nobody sent
     * must not delete anything. The console sends it explicitly and defaults it on, having
     * first shown the list from {@code /restore/preview}.
     */
    @POST
    @Path("/restore")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Restore configuration and messages from an archive onto this engine.")
    @MirthOperation(name = "backupRestore", display = "Restore from a backup",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> restore(
        @Param(value = "options", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description =
            "name, scope, channels, passphrase, safetyBackup, redeploy, deployForMessages, replace.",
            required = true)
        Map<String, String> options) throws ClientException;

    /**
     * What a replacing restore would remove from this engine, without removing it.
     *
     * <p>Takes {@code name}, {@code scope} and {@code passphrase} and answers a row per
     * object: the scope, its id, its name, whether it is deployed, and whether removing it
     * destroys data that cannot be recovered.
     *
     * <p>Exists because {@code replace} deletes channels, and deleting a channel takes its
     * message history with it. The console will not enable a replacing restore until this
     * has been run, so the confirmation is made against the actual list rather than against
     * an idea of it.
     */
    @POST
    @Path("/restore/preview")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "List what a replacing restore would remove from this engine.")
    @MirthOperation(name = "backupInspect", display = "Preview a replacing restore",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> previewRemovals(
        @Param(value = "options", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description =
            "name, scope, passphrase.", required = true)
        Map<String, String> options) throws ClientException;

    /**
     * Removes one archive from the destination. Irreversible, and named explicitly.
     *
     * <p>Refuses any name that is not one this plugin wrote, which is what makes it safe to
     * point a backup at a directory that already holds other things.
     */
    @POST
    @Path("/archives/_delete")
    @io.swagger.v3.oas.annotations.Operation(summary = "Delete one archive.")
    @MirthOperation(name = "backupDeleteArchive", display = "Delete a backup archive",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> deleteArchive(
        @Param("name")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "The archive's file name.", required = true)
        @QueryParam("name") String name) throws ClientException;

    /**
     * Downloads an archive kept on this engine.
     *
     * <p>Only archives in this engine's own staging directory. One on S3 is downloaded from
     * S3, not proxied through the engine -- proxying would put a multi-gigabyte transfer
     * through the engine's request threads for no gain, and resolving an arbitrary name
     * against remote storage would make this a general-purpose fetch endpoint.
     *
     * <p>Streamed rather than buffered: an archive does not fit in a response the engine
     * assembles in memory.
     */
    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Download an archive kept on this engine.")
    @MirthOperation(name = "backupDownload", display = "Download a backup archive")
    Response download(
        @Param("name")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "The archive's file name.", required = true)
        @QueryParam("name") String name) throws ClientException;

    /**
     * Uploads an archive so it can be restored.
     *
     * <p>The path that matters after a disaster: an engine rebuilt from nothing has no
     * destination configured and no archives of its own, and the backup is a file on
     * somebody's laptop.
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Upload an archive to this engine so it can be restored.")
    @MirthOperation(name = "backupUpload", display = "Upload a backup archive",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> upload(
        @Param("name")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "The archive's original file name.", required = true)
        @QueryParam("name") String name,
        @Param(value = "body", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description = "The archive itself.")
        InputStream body) throws ClientException;

    /** Archives uploaded to this engine but not yet restored. */
    @GET
    @Path("/uploads")
    @io.swagger.v3.oas.annotations.Operation(summary = "List uploaded archives.")
    @MirthOperation(name = "backupUploads", display = "List uploaded archives",
        auditable = false)
    Map<String, Object> uploads() throws ClientException;

    /** Removes an uploaded archive from this engine's upload directory. */
    @POST
    @Path("/uploads/_delete")
    @io.swagger.v3.oas.annotations.Operation(summary = "Delete an uploaded archive.")
    @MirthOperation(name = "backupDeleteUpload", display = "Delete an uploaded archive",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> deleteUpload(
        @Param("name")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "The archive's file name.", required = true)
        @QueryParam("name") String name) throws ClientException;
}
