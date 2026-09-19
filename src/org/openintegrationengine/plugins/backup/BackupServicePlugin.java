/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle, and the clock the schedule runs on.
 *
 * <p>The scheduler wakes once a minute and asks whether a backup is owed. It does not
 * schedule itself for the next run time, which would be the obvious design and is the wrong
 * one here: the schedule lives in the database and is shared, so it can change on another
 * node, and a task that had already computed its next firing would keep the old one until
 * something restarted it. Waking on a fixed short interval and re-reading the schedule each
 * time means a change made on the page takes effect within the minute, everywhere, with
 * nothing to invalidate.
 *
 * <p>A wake-up costs one read of the {@code configuration} table and, almost always,
 * nothing else.
 */
public class BackupServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Backup";

    private static final Logger LOG = LogManager.getLogger(BackupServicePlugin.class);

    /**
     * The first check waits.
     *
     * <p>Two reasons, and the second is the one that matters. Startup should be clear of a
     * job that reads every channel. And an engine that is restarting in a loop would
     * otherwise take a backup on each boot: the claim in the shared record stops a
     * <i>cluster</i> duplicating work, but a single node crash-looping would still qualify
     * each time, and two minutes is longer than a crash loop's cycle.
     */
    private static final int STARTUP_DELAY_SECONDS = 120;

    /** How often the schedule is re-read. See the class comment. */
    private static final int WAKE_INTERVAL_SECONDS = 60;

    /**
     * Shared with the servlet, which JAX-RS constructs per request and which therefore
     * cannot hold state of its own.
     */
    private static volatile BackupService service;

    private ScheduledExecutorService scheduler;

    public static BackupService service() {
        return service;
    }

    @Override
    public void init(Properties properties) {
        /*
         * Under appdata/, which compose maps to a named volume, so archives kept on this
         * engine survive the container being recreated. Anywhere else in the image and the
         * "keep on this engine" destination would be a synonym for "throw away on the next
         * deploy" -- which is exactly the destination somebody picks while they are still
         * setting the plugin up.
         */
        String baseDir = ControllerFactory.getFactory()
            .createConfigurationController().getBaseDir();
        Path base = Path.of(baseDir, "appdata", "backup");

        service = new BackupService(base);
        try {
            service.prepare();
        } catch (Exception e) {
            LOG.error("backup: could not prepare {}; the extension will load but backups "
                + "will fail until this is fixed", base, e);
        }
        LOG.info("backup: working directory {}", base);
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup-schedule");
            // Daemon so an in-flight upload cannot hold up engine shutdown. The backup is
            // lost in that case, which is right: a half-uploaded archive is not a backup,
            // and the next run takes a whole one.
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, STARTUP_DELAY_SECONDS,
            WAKE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        BackupSettings settings = BackupSettings.load();
        if (!settings.enabled()) {
            LOG.info("backup: installed, schedule switched off; backups run on request only");
        } else {
            LOG.info("backup: scheduled {} to {}", settings.schedule().describe(),
                settings.destinationType());
        }
    }

    /**
     * One wake-up.
     *
     * <p>Never allowed to throw. An exception escaping a scheduled task cancels every future
     * run, which would leave the backup silently dead -- and a dead backup looks exactly
     * like a working one right up until somebody needs it.
     */
    private void tick() {
        BackupService current = service;
        if (current == null) {
            return;
        }
        try {
            if (!current.isDue()) {
                return;
            }
            Map<String, Object> result = current.runBackup("scheduled", true);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                LOG.warn("backup: the scheduled run did not complete: {}",
                    result.get("error"));
            }
        } catch (Throwable t) {
            LOG.error("backup: the scheduled pass failed", t);
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
    }

    @Override
    public void update(Properties properties) {
        // Settings live in the configuration table and are re-read on every wake-up and on
        // every request, so there is nothing to reload here.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * Three permissions, not one.
     *
     * <p>They are three genuinely different things to be allowed to do, and a deployment
     * with an authorization plugin will want to hand them out separately. <b>Viewing</b> is
     * seeing whether backups are running, which most people should have. <b>Managing</b> is
     * changing the settings and taking one, which is administrative and reaches the network.
     * <b>Restoring</b> is overwriting this engine's configuration from a file, which is the
     * single most destructive thing this extension can do and is the one an on-call
     * engineer should have to be granted deliberately -- along with downloading an archive,
     * which is a copy of every credential and every message the engine holds and is
     * therefore the same level of trust by another route.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(PLUGIN_POINT_NAME, "View backups",
                "Allows seeing the backup settings, schedule, history and archive list.",
                new String[] {"backupStatus", "backupArchives", "backupInspect", "backupUploads"},
                new String[] {}),
            new ExtensionPermission(PLUGIN_POINT_NAME, "Manage backups",
                "Allows changing the settings, testing the destination and taking a backup.",
                new String[] {"backupSetSettings", "backupRunNow", "backupProbe",
                    "backupDeleteArchive"},
                new String[] {}),
            new ExtensionPermission(PLUGIN_POINT_NAME, "Restore from a backup",
                "Allows downloading an archive and restoring one onto this engine. An "
                + "archive holds every credential and, when messages are included, every "
                + "message the engine has processed.",
                new String[] {"backupRestore", "backupDownload", "backupUpload", "backupDeleteUpload"},
                new String[] {})
        };
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
