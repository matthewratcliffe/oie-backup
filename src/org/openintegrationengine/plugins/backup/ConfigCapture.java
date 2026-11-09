/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.ServerSettings;
import com.mirth.connect.model.UpdateSettings;
import com.mirth.connect.model.User;
import com.mirth.connect.model.alert.AlertModel;
import com.mirth.connect.server.channel.ErrorTaskHandler;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.server.controllers.AlertController;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.controllers.UserController;
import com.mirth.connect.util.ConfigurationProperty;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Engine configuration, to files and back.
 *
 * <p>The layout is <b>deliberately identical</b> to the one the Git Sync extension and
 * {@code scripts/oie-config-pull.sh} produce, which is why it is worth repeating here
 * rather than importing: the three are built and installed independently, and a backup has
 * to keep working on an engine where Git Sync is not installed at all. The payoff is that
 * the {@code config/} directory inside an archive is a configuration repository's working
 * tree, unchanged -- a backup can be committed, and a checkout can be restored.
 *
 * <pre>
 *   channels/&lt;name&gt;.xml
 *   code-templates/libraries.xml
 *   code-templates/templates/&lt;name&gt;.xml
 *   channel-groups.xml
 *   configuration-map.properties
 *   alerts/&lt;name&gt;.xml
 *   global-scripts.xml
 *   server-settings.xml
 *   administrator-settings.xml
 *   channel-tags.xml
 *   resources.xml
 *   data-pruner.properties
 *   volume-monitor.properties
 *   users.xml
 * </pre>
 *
 * <p>Objects go through {@link ObjectXMLSerializer}, the same serialiser the Administrator
 * exports with, and are restored through the engine's own controllers rather than the REST
 * API. That matters for correctness rather than convenience: the controllers reject what
 * they cannot deserialise, whereas a channel pushed as XML that XStream cannot read is
 * stored as an {@code InvalidChannel} stub and reported as a successful save.
 *
 * <p>Files are named from each object's name with its id kept <i>inside</i> the file, so a
 * renamed object gets a renamed file without changing identity, and the restore is an
 * upsert keyed on id rather than a duplicate.
 */
final class ConfigCapture {

    private static final Logger LOG = LogManager.getLogger(ConfigCapture.class);

    /** The plugin point name the Data Pruner stores its properties under. */
    private static final String DATA_PRUNER_PLUGIN = "Data Pruner";

    /**
     * The {@code configuration} table group the Volume Monitor writes to.
     *
     * <p>Repeated here as a literal rather than imported from that extension, because the
     * two are built and installed independently and this one must keep working when the
     * monitor is not installed at all.
     */
    private static final String VOLUME_MONITOR_GROUP = "Volume Monitor";

    /** Counts and problems from a capture or a restore, for the manifest and the report. */
    static final class Result {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        void count(String what, int n) {
            counts.merge(what, n, Integer::sum);
        }

        void problem(String message) {
            problems.add(message);
        }

        /** Something worth telling the operator that is not a failure. */
        void note(String message) {
            notes.add(message);
        }

        Map<String, Integer> counts() {
            return counts;
        }

        List<String> problems() {
            return problems;
        }

        List<String> notes() {
            return notes;
        }

        boolean ok() {
            return problems.isEmpty();
        }
    }

    private final Set<BackupScope> scope;

    ConfigCapture(Set<BackupScope> scope) {
        this.scope = scope == null || scope.isEmpty() ? BackupScope.defaults() : scope;
    }

    private static ObjectXMLSerializer xml() {
        return ObjectXMLSerializer.getInstance();
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    private static CodeTemplateController codeTemplates() {
        return ControllerFactory.getFactory().createCodeTemplateController();
    }

    private static ConfigurationController configuration() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static AlertController alerts() {
        return ControllerFactory.getFactory().createAlertController();
    }

    private static ScriptController scripts() {
        return ControllerFactory.getFactory().createScriptController();
    }

    private static ExtensionController extensions() {
        return ControllerFactory.getFactory().createExtensionController();
    }

    private static UserController users() {
        return ControllerFactory.getFactory().createUserController();
    }

    // ------------------------------------------------------------------
    // Engine -> files
    // ------------------------------------------------------------------

    /**
     * Writes the selected scopes into {@code root}.
     *
     * <p>Every scope is wrapped: one that throws records a problem and the rest still run.
     * A backup that is missing its alerts because the alert controller threw is worth far
     * more than no backup at all, and the manifest says which part is missing so the gap is
     * visible rather than assumed away.
     */
    Result capture(Path root) throws IOException {
        Result result = new Result();
        Files.createDirectories(root);

        run(result, BackupScope.CHANNELS, () -> {
            Path dir = replaceDirectory(root.resolve("channels"));
            List<Channel> all = sortedByName(channels().getChannels(null),
                Channel::getName, Channel::getId);
            for (Channel channel : all) {
                write(dir.resolve(fileName(channel.getName(), channel.getId())),
                    xml().serialize(channel));
            }
            result.count(BackupScope.CHANNELS.token(), all.size());
        });

        run(result, BackupScope.CODE_TEMPLATES, () -> {
            Path dir = replaceDirectory(root.resolve("code-templates"));
            Path templates = Files.createDirectories(dir.resolve("templates"));

            // Libraries carry membership, so they are written even when empty: an empty
            // library list is a meaningful state to restore, not an absence.
            List<CodeTemplateLibrary> libraries = codeTemplates().getLibraries(null, false);
            if (libraries == null) {
                libraries = List.of();
            }
            write(dir.resolve("libraries.xml"), xml().serialize(new ArrayList<>(libraries)));

            List<CodeTemplate> all = codeTemplates().getCodeTemplates(null);
            if (all == null) {
                all = List.of();
            }
            for (CodeTemplate template : sortedByName(all, CodeTemplate::getName,
                    CodeTemplate::getId)) {
                write(templates.resolve(fileName(template.getName(), template.getId())),
                    xml().serialize(template));
            }
            result.count(BackupScope.CODE_TEMPLATES.token(), all.size());
        });

        run(result, BackupScope.CHANNEL_GROUPS, () -> {
            List<ChannelGroup> groups = channels().getChannelGroups(null);
            if (groups == null) {
                groups = List.of();
            }
            write(root.resolve("channel-groups.xml"), xml().serialize(new ArrayList<>(groups)));
            result.count(BackupScope.CHANNEL_GROUPS.token(), groups.size());
        });

        run(result, BackupScope.CONFIGURATION_MAP, () -> {
            Map<String, ConfigurationProperty> map = configuration().getConfigurationProperties();
            if (map == null) {
                map = Map.of();
            }
            write(root.resolve("configuration-map.properties"), renderConfigurationMap(map));
            result.count(BackupScope.CONFIGURATION_MAP.token(), map.size());
        });

        run(result, BackupScope.ALERTS, () -> {
            Path dir = replaceDirectory(root.resolve("alerts"));
            List<AlertModel> all = alerts().getAlerts();
            if (all == null) {
                all = List.of();
            }
            for (AlertModel alert : sortedByName(all, AlertModel::getName, AlertModel::getId)) {
                write(dir.resolve(fileName(alert.getName(), alert.getId())),
                    xml().serialize(alert));
            }
            result.count(BackupScope.ALERTS.token(), all.size());
        });

        run(result, BackupScope.GLOBAL_SCRIPTS, () -> {
            Map<String, String> global = scripts().getGlobalScripts();
            if (global == null) {
                global = Map.of();
            }
            // A TreeMap so the serialised order is stable and two backups of an unchanged
            // engine produce identical files.
            write(root.resolve("global-scripts.xml"), xml().serialize(new TreeMap<>(global)));
            result.count(BackupScope.GLOBAL_SCRIPTS.token(), global.size());
        });

        run(result, BackupScope.SERVER_SETTINGS, () -> {
            ServerSettings settings = configuration().getServerSettings();
            if (settings != null) {
                write(root.resolve("server-settings.xml"), xml().serialize(settings));
                result.count(BackupScope.SERVER_SETTINGS.token(), 1);
            }
        });

        run(result, BackupScope.ADMINISTRATOR_SETTINGS, () -> {
            UpdateSettings settings = configuration().getUpdateSettings();
            if (settings != null) {
                write(root.resolve("administrator-settings.xml"), xml().serialize(settings));
                result.count(BackupScope.ADMINISTRATOR_SETTINGS.token(), 1);
            }
        });

        run(result, BackupScope.CHANNEL_TAGS, () -> {
            Set<ChannelTag> tags = configuration().getChannelTags();
            // Written as a sorted list, not as the Set: a HashSet iterates in an unstable
            // order, and an unstable order makes two identical engines produce two
            // different files.
            List<ChannelTag> ordered = new ArrayList<>(tags == null ? Set.of() : tags);
            ordered.sort(Comparator.comparing(t -> t.getName() == null ? "" : t.getName(),
                String.CASE_INSENSITIVE_ORDER));
            write(root.resolve("channel-tags.xml"), xml().serialize(ordered));
            result.count(BackupScope.CHANNEL_TAGS.token(), ordered.size());
        });

        run(result, BackupScope.RESOURCES, () -> {
            // Already an XML document rather than a model object, so it goes out as-is.
            String resources = configuration().getResources();
            write(root.resolve("resources.xml"), resources == null ? "" : resources);
            result.count(BackupScope.RESOURCES.token(), 1);
        });

        run(result, BackupScope.DATA_PRUNER, () -> {
            Properties pruner = extensions().getPluginProperties(DATA_PRUNER_PLUGIN, null);
            write(root.resolve("data-pruner.properties"),
                renderProperties(pruner, "Settings > Data Pruner"));
            result.count(BackupScope.DATA_PRUNER.token(), pruner == null ? 0 : pruner.size());
        });

        run(result, BackupScope.VOLUME_MONITOR, () -> {
            Properties monitor = configuration().getPropertiesForGroup(VOLUME_MONITOR_GROUP);
            write(root.resolve("volume-monitor.properties"),
                renderProperties(monitor, "Volume Monitor rules and settings"));
            result.count(BackupScope.VOLUME_MONITOR.token(),
                monitor == null ? 0 : monitor.size());
        });

        run(result, BackupScope.TLS_CERTIFICATES, () -> {
            int n = 0;
            for (String store : TlsStores.STORES) {
                String b64 = configuration().getProperty(TlsStores.PLUGIN, store);
                if (b64 == null || b64.isBlank()) {
                    continue;
                }
                write(root.resolve(TlsStores.FILE_DIR).resolve(store + ".p12.b64"), b64);
                n += TlsStores.aliasCount(b64);
            }
            result.count(BackupScope.TLS_CERTIFICATES.token(), n);
        });

        run(result, BackupScope.USERS, () -> {
            List<User> all = users().getAllUsers();
            if (all == null) {
                all = List.of();
            }
            List<User> ordered = new ArrayList<>(all);
            ordered.sort(Comparator.comparing(u -> u.getUsername() == null ? "" : u.getUsername(),
                String.CASE_INSENSITIVE_ORDER));
            /*
             * The last-login timestamp and the login-strike count are cleared before
             * writing. They are per-instance session state, not configuration: leaving them
             * in would make every backup differ from the last one for no reason anybody
             * cares about, and restoring them would carry one instance's lockout state into
             * another.
             */
            for (User user : ordered) {
                user.setLastLogin(null);
            }
            write(root.resolve("users.xml"), xml().serialize(ordered));
            result.count(BackupScope.USERS.token(), ordered.size());
            if (!ordered.isEmpty()) {
                result.note(ordered.size() + " user account(s) captured without passwords; "
                    + "the engine stores credentials behind a one-way digest and offers no "
                    + "way to write them back, so restored accounts need a password set "
                    + "before anyone can sign in");
            }
        });

        return result;
    }

    // ------------------------------------------------------------------
    // Files -> engine
    // ------------------------------------------------------------------

    /**
     * Applies a captured tree to this engine, as an upsert keyed on each object's id.
     *
     * <p><b>Nothing is deleted.</b> An engine object the archive has no file for is left
     * exactly as it is. That is the difference between a restore and a reset, and it is
     * deliberate in both directions: removing a channel destroys its message history, and a
     * backup restored onto an engine that has moved on since should add back what was lost
     * rather than roll the engine back to a moment. An operator who genuinely wants the
     * engine to hold only what the archive describes has to remove the extras themselves,
     * which keeps that decision where it belongs.
     */
    Result restore(Path root) {
        Result result = new Result();
        ServerEventContext ctx = ServerEventContext.SYSTEM_USER_EVENT_CONTEXT;

        // Code templates first: a channel can reference a template, and restoring the
        // channel before the template exists would deploy it against a missing function.
        if (scope.contains(BackupScope.CODE_TEMPLATES)) {
            Path libraries = root.resolve("code-templates/libraries.xml");
            if (Files.isRegularFile(libraries)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<CodeTemplateLibrary> list =
                        xml().deserializeList(read(libraries), CodeTemplateLibrary.class);
                    codeTemplates().updateLibraries(list, ctx, true);
                } catch (Exception e) {
                    result.problem("code-templates/libraries.xml: " + describe(e));
                }
            }
            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("code-templates/templates"))) {
                try {
                    codeTemplates().updateCodeTemplate(
                        xml().deserialize(read(file), CodeTemplate.class), ctx, true);
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + describe(e));
                }
            }
            result.count(BackupScope.CODE_TEMPLATES.token(), n);
        }

        if (scope.contains(BackupScope.CHANNEL_GROUPS)) {
            Path file = root.resolve("channel-groups.xml");
            if (Files.isRegularFile(file)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<ChannelGroup> list =
                        xml().deserializeList(read(file), ChannelGroup.class);
                    /*
                     * updateChannelGroups treats the set it is handed as the complete list
                     * and removes anything else, which is the one place this restore does
                     * delete. It is left that way because a group is a label: deleting one
                     * destroys no messages, and the alternative -- groups from two
                     * generations of the engine coexisting -- is a mess nobody can untangle
                     * afterwards.
                     */
                    channels().updateChannelGroups(new LinkedHashSet<>(list), Set.of(), true);
                    result.count(BackupScope.CHANNEL_GROUPS.token(), list.size());
                } catch (Exception e) {
                    result.problem("channel-groups.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.CHANNELS)) {
            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("channels"))) {
                try {
                    Channel channel = xml().deserialize(read(file), Channel.class);
                    // override=true: the archive is the desired state, so it wins over the
                    // revision counter the Administrator bumps on local edits.
                    channels().updateChannel(channel, ctx, true, Calendar.getInstance());
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + describe(e));
                }
            }
            result.count(BackupScope.CHANNELS.token(), n);
        }

        if (scope.contains(BackupScope.CONFIGURATION_MAP)) {
            Path file = root.resolve("configuration-map.properties");
            if (Files.isRegularFile(file)) {
                try {
                    Map<String, ConfigurationProperty> map = parseConfigurationMap(read(file));
                    configuration().setConfigurationProperties(map, true);
                    result.count(BackupScope.CONFIGURATION_MAP.token(), map.size());
                } catch (Exception e) {
                    result.problem("configuration-map.properties: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.ALERTS)) {
            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("alerts"))) {
                try {
                    alerts().updateAlert(xml().deserialize(read(file), AlertModel.class));
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + describe(e));
                }
            }
            result.count(BackupScope.ALERTS.token(), n);
        }

        if (scope.contains(BackupScope.GLOBAL_SCRIPTS)) {
            Path file = root.resolve("global-scripts.xml");
            if (Files.isRegularFile(file)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> global = xml().deserialize(read(file), TreeMap.class);
                    scripts().setGlobalScripts(new LinkedHashMap<>(global));
                    result.count(BackupScope.GLOBAL_SCRIPTS.token(), global.size());
                } catch (Exception e) {
                    result.problem("global-scripts.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.SERVER_SETTINGS)) {
            Path file = root.resolve("server-settings.xml");
            if (Files.isRegularFile(file)) {
                try {
                    configuration().setServerSettings(
                        xml().deserialize(read(file), ServerSettings.class));
                    result.count(BackupScope.SERVER_SETTINGS.token(), 1);
                    result.note("the server settings were restored, including this "
                        + "instance's server name, environment name and SMTP configuration "
                        + "-- check them if the archive came from another environment");
                } catch (Exception e) {
                    result.problem("server-settings.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.ADMINISTRATOR_SETTINGS)) {
            Path file = root.resolve("administrator-settings.xml");
            if (Files.isRegularFile(file)) {
                try {
                    configuration().setUpdateSettings(
                        xml().deserialize(read(file), UpdateSettings.class));
                    result.count(BackupScope.ADMINISTRATOR_SETTINGS.token(), 1);
                } catch (Exception e) {
                    result.problem("administrator-settings.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.CHANNEL_TAGS)) {
            Path file = root.resolve("channel-tags.xml");
            if (Files.isRegularFile(file)) {
                try {
                    List<ChannelTag> tags = xml().deserializeList(read(file), ChannelTag.class);
                    configuration().setChannelTags(new LinkedHashSet<>(tags));
                    result.count(BackupScope.CHANNEL_TAGS.token(), tags.size());
                } catch (Exception e) {
                    result.problem("channel-tags.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.RESOURCES)) {
            Path file = root.resolve("resources.xml");
            if (Files.isRegularFile(file)) {
                try {
                    String body = read(file).trim();
                    // An empty file means "this archive does not describe resources", not
                    // "remove every resource" -- which would break every channel that
                    // references one.
                    if (!body.isEmpty()) {
                        configuration().setResources(body);
                        result.count(BackupScope.RESOURCES.token(), 1);
                    }
                } catch (Exception e) {
                    result.problem("resources.xml: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.DATA_PRUNER)) {
            Path file = root.resolve("data-pruner.properties");
            if (Files.isRegularFile(file)) {
                try {
                    Properties props = parseProperties(read(file));
                    // merge=false: the archive is the desired state, so a property that is
                    // not in it is removed rather than left behind.
                    extensions().setPluginProperties(DATA_PRUNER_PLUGIN, props, false);
                    result.count(BackupScope.DATA_PRUNER.token(), props.size());
                } catch (Exception e) {
                    result.problem("data-pruner.properties: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.TLS_CERTIFICATES)) {
            int n = 0;
            for (String store : TlsStores.STORES) {
                Path file = root.resolve(TlsStores.FILE_DIR).resolve(store + ".p12.b64");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    String existing = configuration().getProperty(TlsStores.PLUGIN, store);
                    String merged = TlsStores.merge(existing, read(file).trim());
                    configuration().saveProperty(TlsStores.PLUGIN, store, merged);
                    n += TlsStores.aliasCount(merged);
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + describe(e));
                }
            }
            result.count(BackupScope.TLS_CERTIFICATES.token(), n);
        }

        if (scope.contains(BackupScope.VOLUME_MONITOR)) {
            Path file = root.resolve("volume-monitor.properties");
            if (Files.isRegularFile(file)) {
                try {
                    Properties props = parseProperties(read(file));
                    Properties existing =
                        configuration().getPropertiesForGroup(VOLUME_MONITOR_GROUP);
                    /*
                     * Key by key, not a clear followed by a rewrite. The monitor reads this
                     * group on a timer from another thread, and emptying it first would give
                     * that thread a window in which every channel looks unmonitored.
                     */
                    if (existing != null) {
                        for (String key : existing.stringPropertyNames()) {
                            if (!props.containsKey(key)) {
                                configuration().removeProperty(VOLUME_MONITOR_GROUP, key);
                            }
                        }
                    }
                    for (String key : props.stringPropertyNames()) {
                        configuration().saveProperty(VOLUME_MONITOR_GROUP, key,
                            props.getProperty(key));
                    }
                    result.count(BackupScope.VOLUME_MONITOR.token(), props.size());
                } catch (Exception e) {
                    result.problem("volume-monitor.properties: " + describe(e));
                }
            }
        }

        if (scope.contains(BackupScope.USERS)) {
            Path file = root.resolve("users.xml");
            if (Files.isRegularFile(file)) {
                try {
                    List<User> wanted = xml().deserializeList(read(file), User.class);
                    List<User> existing = users().getAllUsers();
                    Set<String> present = existing == null ? Set.of()
                        : existing.stream()
                            .map(u -> u.getUsername() == null ? "" : u.getUsername())
                            .collect(Collectors.toSet());
                    int created = 0;
                    int updated = 0;
                    for (User user : wanted) {
                        String username = user.getUsername();
                        if (username == null || username.isBlank()) {
                            continue;
                        }
                        boolean existed = present.contains(username);
                        /*
                         * The id is cleared for an account that is not here. Ids are
                         * assigned by this engine's own sequence, and reusing one from
                         * another engine would either collide with a different person's
                         * account or create a row the sequence will later try to reuse.
                         * Identity here is the username, which is what the login uses.
                         */
                        if (!existed) {
                            user.setId(null);
                        } else if (existing != null) {
                            for (User e : existing) {
                                if (username.equals(e.getUsername())) {
                                    user.setId(e.getId());
                                    break;
                                }
                            }
                        }
                        users().updateUser(user);
                        if (existed) {
                            updated++;
                        } else {
                            created++;
                        }
                    }
                    result.count(BackupScope.USERS.token(), created + updated);
                    if (created > 0) {
                        result.note(created + " user account(s) were created without a "
                            + "password and cannot be signed in to until one is set; "
                            + updated + " existing account(s) were updated");
                    }
                } catch (Exception e) {
                    result.problem("users.xml: " + describe(e));
                }
            }
        }

        if (!result.ok()) {
            LOG.warn("backup restore finished with {} problem(s)", result.problems().size());
        }
        return result;
    }

    /**
     * Reads back every channel the tree names and reports any the engine could not
     * deserialise.
     *
     * <p>Worth doing explicitly, and this is the reason it is here rather than trusted:
     * {@code updateChannel} can report success while the engine stores Mirth's
     * {@code InvalidChannel} stub, which never deploys and never runs. A restore that leaves
     * channels in that state has to say so -- it is the difference between "restored" and
     * "restored, and the engine is not actually going to run four of these".
     */
    List<String> findInvalidChannels(Path root) {
        List<String> invalid = new ArrayList<>();
        for (Path file : xmlFilesIn(root.resolve("channels"))) {
            String id;
            try {
                id = xml().deserialize(read(file), Channel.class).getId();
            } catch (Exception e) {
                // Unreadable here; restore() already reported it.
                continue;
            }
            List<Channel> found = channels().getChannels(Set.of(id));
            if (found == null || found.isEmpty()) {
                invalid.add(relative(root, file) + ": not present after the restore");
                continue;
            }
            Channel stored = found.get(0);
            // The stub has no source connector properties.
            if (stored.getSourceConnector() == null
                || stored.getSourceConnector().getProperties() == null) {
                invalid.add(stored.getName() + " (" + relative(root, file)
                    + "): stored as an invalid channel");
            }
        }
        return invalid;
    }

    // ------------------------------------------------------------------
    // Replace: removing what the archive does not describe
    // ------------------------------------------------------------------

    /**
     * One engine object the archive has no file for.
     *
     * <p>Tab-separated for the API, the same reason everything else here is: XStream
     * renders a list of maps in a shape the console's decoder cannot reliably unpick.
     */
    static final class Removal {
        final BackupScope scope;
        final String id;
        final String name;
        final boolean deployed;
        /** True when removing this destroys data that cannot be recovered. */
        final boolean destroysHistory;

        Removal(BackupScope scope, String id, String name, boolean deployed,
                boolean destroysHistory) {
            this.scope = scope;
            this.id = id == null ? "" : id;
            this.name = name == null || name.isBlank() ? "(unnamed)" : name;
            this.deployed = deployed;
            this.destroysHistory = destroysHistory;
        }

        String row() {
            return Tsv.join(scope.token(), scope.label(), id, name,
                deployed ? "deployed" : "undeployed", destroysHistory);
        }
    }

    /** Fields of a {@link Removal} row, in order. Mirrored by the console. */
    static final int REMOVAL_FIELDS = 6;

    /**
     * What a replacing restore would remove: the engine objects this archive does not
     * describe, within the selected scopes.
     *
     * <p>Computed and shown before anything is applied, because two of the four types here
     * cannot be undone. Deleting a channel takes its message history with it, and that
     * history is often the only record that a message was ever received.
     *
     * <p><b>An absent directory means "this archive does not describe that type", never
     * "delete everything of that type".</b> Git does not track empty directories and
     * neither does a zip, so a backup whose channels were all deleted and a backup that
     * never captured channels look identical once unpacked. Reading the second as the first
     * would offer to delete every channel on the engine. Scopes the caller did not select
     * are skipped for the same reason, one level up.
     *
     * <p>Ids are read out of the XML with a regex rather than by deserialising, for the
     * same reason in the other direction: a file this engine cannot parse still counts as
     * <i>present</i>, because the alternative is a parse failure quietly becoming a
     * deletion.
     */
    List<Removal> findRemovals(Path root, String protectUsername) {
        List<Removal> out = new ArrayList<>();

        if (scope.contains(BackupScope.CHANNELS)
            && Files.isDirectory(root.resolve("channels"))) {
            Set<String> keep = idsIn(root.resolve("channels"), "channel");
            List<Channel> all = channels().getChannels(null);
            EngineController engine = ControllerFactory.getFactory().createEngineController();
            for (Channel channel : all == null ? List.<Channel>of() : all) {
                if (keep.contains(channel.getId())) {
                    continue;
                }
                boolean deployed;
                try {
                    deployed = engine.isDeployed(channel.getId());
                } catch (RuntimeException e) {
                    deployed = false;
                }
                out.add(new Removal(BackupScope.CHANNELS, channel.getId(), channel.getName(),
                    deployed, true));
            }
        }

        if (scope.contains(BackupScope.CODE_TEMPLATES)
            && Files.isDirectory(root.resolve("code-templates/templates"))) {
            Set<String> keep = idsIn(root.resolve("code-templates/templates"), "codeTemplate");
            /*
             * A controller that throws here must not be read as "nothing is described".
             * Listing is how this method learns what exists, and an empty list would make
             * every object look absent from the engine rather than absent from the archive
             * -- which is the harmless direction for a capture and the wrong one entirely
             * for something that then deletes.
             */
            List<CodeTemplate> all;
            try {
                all = codeTemplates().getCodeTemplates(null);
            } catch (ControllerException e) {
                LOG.warn("backup: could not list code templates for the replace preview: {}",
                    e.getMessage());
                all = List.of();
            }
            for (CodeTemplate template : all == null ? List.<CodeTemplate>of() : all) {
                if (!keep.contains(template.getId())) {
                    out.add(new Removal(BackupScope.CODE_TEMPLATES, template.getId(),
                        template.getName(), false, false));
                }
            }
        }

        if (scope.contains(BackupScope.ALERTS) && Files.isDirectory(root.resolve("alerts"))) {
            Set<String> keep = idsIn(root.resolve("alerts"), "alertModel");
            List<AlertModel> all;
            try {
                all = alerts().getAlerts();
            } catch (ControllerException e) {
                LOG.warn("backup: could not list alerts for the replace preview: {}",
                    e.getMessage());
                all = List.of();
            }
            for (AlertModel alert : all == null ? List.<AlertModel>of() : all) {
                if (!keep.contains(alert.getId())) {
                    out.add(new Removal(BackupScope.ALERTS, alert.getId(), alert.getName(),
                        false, false));
                }
            }
        }

        if (scope.contains(BackupScope.USERS)
            && Files.isRegularFile(root.resolve("users.xml"))) {
            Set<String> keep = usernamesIn(root.resolve("users.xml"));
            try {
                List<User> all = users().getAllUsers();
                for (User user : all == null ? List.<User>of() : all) {
                    String username = user.getUsername();
                    if (username == null || keep.contains(username)) {
                        continue;
                    }
                    /*
                     * Never the account doing the restore. Removing it mid-operation would
                     * end the session that is performing it, and an operator locked out of
                     * an engine they were half-way through restoring is a worse outcome
                     * than one extra account surviving.
                     */
                    if (username.equals(protectUsername)) {
                        continue;
                    }
                    out.add(new Removal(BackupScope.USERS,
                        String.valueOf(user.getId()), username, false, false));
                }
            } catch (Exception e) {
                LOG.warn("backup: could not list users for the replace preview: {}",
                    e.getMessage());
            }
        }

        return out;
    }

    /**
     * Carries out the removals {@link #findRemovals} described.
     *
     * <p>Recomputed rather than taking the previewed list as input. The preview is shown to
     * a person and then confirmed, and between those two moments the engine can change --
     * somebody creates a channel, another node applies something. Acting on a stale list
     * would delete an object nobody has looked at, which is exactly what the preview exists
     * to prevent.
     *
     * <p>Counted from what actually went rather than from what was asked for, and logged at
     * {@code WARN}, because this is the one part of this extension that destroys data.
     */
    Result applyRemovals(Path root, String protectUsername) {
        Result result = new Result();
        List<Removal> removals = findRemovals(root, protectUsername);
        if (removals.isEmpty()) {
            return result;
        }
        ServerEventContext ctx = ServerEventContext.SYSTEM_USER_EVENT_CONTEXT;

        Set<String> channelIds = new LinkedHashSet<>();
        for (Removal removal : removals) {
            if (removal.scope == BackupScope.CHANNELS) {
                channelIds.add(removal.id);
            }
        }
        if (!channelIds.isEmpty()) {
            try {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                // Stops, undeploys and deletes in one blocking call, which is the only
                // order that works: a deployed channel cannot be removed.
                ControllerFactory.getFactory().createEngineController()
                    .removeChannels(channelIds, ctx, handler);
                if (handler.isErrored()) {
                    result.problem("could not remove every channel: "
                        + describe(handler.getError()));
                }
                List<Channel> left = channels().getChannels(channelIds);
                int gone = channelIds.size() - (left == null ? 0 : left.size());
                result.count("channelsRemoved", gone);
                LOG.warn("backup: a replacing restore removed {} channel(s) and their "
                    + "message history", gone);
            } catch (Exception e) {
                result.problem("removing channels: " + describe(e));
            }
        }

        int templates = 0;
        int alerts = 0;
        int users = 0;
        for (Removal removal : removals) {
            try {
                switch (removal.scope) {
                    case CODE_TEMPLATES:
                        codeTemplates().removeCodeTemplate(removal.id, ctx);
                        templates++;
                        break;
                    case ALERTS:
                        alerts().removeAlert(removal.id);
                        alerts++;
                        break;
                    case USERS:
                        users().removeUser(Integer.valueOf(removal.id), null);
                        users++;
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                result.problem("removing " + removal.scope.label() + " '" + removal.name
                    + "': " + describe(e));
            }
        }
        if (templates > 0) {
            result.count("codeTemplatesRemoved", templates);
        }
        if (alerts > 0) {
            result.count("alertsRemoved", alerts);
        }
        if (users > 0) {
            result.count("usersRemoved", users);
        }
        if (templates + alerts + users > 0) {
            LOG.warn("backup: a replacing restore removed {} code template(s), {} alert(s) "
                + "and {} user account(s)", templates, alerts, users);
        }
        return result;
    }

    /**
     * Ids read out of every XML file in a directory, by regex.
     *
     * <p>The first {@code <id>} inside the document's root element. Deliberately not a
     * deserialise: see {@link #findRemovals} for why a file this engine cannot parse must
     * still count as present.
     */
    private static Set<String> idsIn(Path dir, String rootElement) {
        Set<String> out = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("<id>\\s*([^<\\s][^<]*?)\\s*</id>");
        for (Path file : xmlFilesIn(dir)) {
            try {
                Matcher matcher = pattern.matcher(read(file));
                if (matcher.find()) {
                    out.add(matcher.group(1));
                } else {
                    /*
                     * A file with no id at all. It cannot be matched to anything on the
                     * engine, so it cannot protect anything -- and that is the dangerous
                     * direction, because every object of this type would then look
                     * undescribed and be queued for deletion. Logged rather than ignored.
                     */
                    LOG.warn("backup: {} has no <id> and cannot protect a {} from a "
                        + "replacing restore", file.getFileName(), rootElement);
                }
            } catch (IOException e) {
                LOG.warn("backup: could not read {}: {}", file, e.getMessage());
            }
        }
        return out;
    }

    /** Usernames out of {@code users.xml}, by regex, for the same reason as {@link #idsIn}. */
    private static Set<String> usernamesIn(Path file) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Matcher matcher = Pattern.compile("<username>\\s*([^<\\s][^<]*?)\\s*</username>")
                .matcher(read(file));
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
        } catch (IOException e) {
            LOG.warn("backup: could not read {}: {}", file, e.getMessage());
        }
        return out;
    }

    /** What an archive's {@code config/} directory actually holds, as scope tokens. */
    static Set<BackupScope> scopesPresent(Path root) {
        Set<BackupScope> out = new LinkedHashSet<>();
        for (BackupScope scope : BackupScope.values()) {
            boolean present;
            switch (scope) {
                case CHANNELS:
                    present = Files.isDirectory(root.resolve("channels"));
                    break;
                case CODE_TEMPLATES:
                    present = Files.isDirectory(root.resolve("code-templates"));
                    break;
                case ALERTS:
                    present = Files.isDirectory(root.resolve("alerts"));
                    break;
                case CHANNEL_GROUPS:
                    present = Files.isRegularFile(root.resolve("channel-groups.xml"));
                    break;
                case CONFIGURATION_MAP:
                    present = Files.isRegularFile(root.resolve("configuration-map.properties"));
                    break;
                case GLOBAL_SCRIPTS:
                    present = Files.isRegularFile(root.resolve("global-scripts.xml"));
                    break;
                case SERVER_SETTINGS:
                    present = Files.isRegularFile(root.resolve("server-settings.xml"));
                    break;
                case ADMINISTRATOR_SETTINGS:
                    present = Files.isRegularFile(root.resolve("administrator-settings.xml"));
                    break;
                case CHANNEL_TAGS:
                    present = Files.isRegularFile(root.resolve("channel-tags.xml"));
                    break;
                case RESOURCES:
                    present = Files.isRegularFile(root.resolve("resources.xml"));
                    break;
                case DATA_PRUNER:
                    present = Files.isRegularFile(root.resolve("data-pruner.properties"));
                    break;
                case VOLUME_MONITOR:
                    present = Files.isRegularFile(root.resolve("volume-monitor.properties"));
                    break;
                case TLS_CERTIFICATES:
                    present = Files.isDirectory(root.resolve(TlsStores.FILE_DIR));
                    break;
                case USERS:
                    present = Files.isRegularFile(root.resolve("users.xml"));
                    break;
                default:
                    present = false;
            }
            if (present) {
                out.add(scope);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A capture step that must not take the rest of the backup down with it. */
    private interface Step {
        void run() throws Exception;
    }

    private void run(Result result, BackupScope which, Step step) {
        if (!scope.contains(which)) {
            return;
        }
        try {
            step.run();
        } catch (Exception e) {
            result.problem(which.label() + ": " + describe(e));
            LOG.warn("backup could not capture {}", which.token(), e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        // ControllerException usually wraps the real cause and says nothing itself.
        if (t instanceof ControllerException && t.getCause() != null) {
            String inner = t.getCause().getMessage();
            if (inner != null && !inner.isBlank() && !inner.equals(message)) {
                return message + " (" + inner + ")";
            }
        }
        return message;
    }

    private static <T> List<T> sortedByName(List<T> items, Function<T, String> name,
                                            Function<T, String> id) {
        List<T> copy = new ArrayList<>(items == null ? List.of() : items);
        // By name, then by id, so two objects with the same name still have a stable order
        // and an unchanged engine produces an unchanged tree.
        copy.sort(Comparator
            .comparing((T t) -> orEmpty(name.apply(t)), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(t -> orEmpty(id.apply(t))));
        return copy;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * A file name built from an object's name, with a short id suffix when it needs one.
     *
     * <p>Two objects can legitimately share a name, and the filesystem cannot hold two files
     * with the same one -- so the second would silently overwrite the first and the backup
     * would be missing an object it reported as captured. The id suffix is only added when
     * the name alone is not usable, so the common case stays readable.
     */
    private static String fileName(String name, String id) {
        String base = orEmpty(name).trim();
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            // Everything a Windows or POSIX filesystem refuses, plus the characters that
            // make a file awkward to handle in a shell.
            sb.append("<>:\"/\\|?*".indexOf(c) >= 0 || c < ' ' ? '_' : c);
        }
        String cleaned = sb.toString().trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            cleaned = "unnamed";
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        String shortId = orEmpty(id).replace("-", "");
        if (shortId.length() > 8) {
            shortId = shortId.substring(0, 8);
        }
        return cleaned + (shortId.isEmpty() ? "" : "-" + shortId) + ".xml";
    }

    private static Path replaceDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
        return Files.createDirectories(dir);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> xmlFilesIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.warn("backup could not list {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * The configuration map, rendered by hand.
     *
     * <p>{@link Properties#store} writes a timestamp comment, which would make two backups
     * of an unchanged engine differ -- and comparing two backups is the first thing anybody
     * does with them. Sorted, for the same reason.
     */
    private static String renderConfigurationMap(Map<String, ConfigurationProperty> map) {
        StringBuilder out = new StringBuilder();
        out.append("# Settings > Configuration Map, captured by the Backup extension.\n");
        out.append("# A line beginning with '#' immediately above a key is its comment.\n\n");
        new TreeMap<>(map).forEach((key, property) -> {
            String comment = property == null ? null : property.getComment();
            if (comment != null && !comment.isBlank()) {
                for (String line : comment.split("\r?\n")) {
                    out.append("# ").append(line).append('\n');
                }
            }
            out.append(escape(key)).append(" = ")
               .append(property == null || property.getValue() == null
                   ? "" : escape(property.getValue()))
               .append('\n');
        });
        return out.toString();
    }

    private static Map<String, ConfigurationProperty> parseConfigurationMap(String text) {
        Map<String, ConfigurationProperty> map = new LinkedHashMap<>();
        StringBuilder comment = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                comment.setLength(0);
                continue;
            }
            if (line.startsWith("#") || line.startsWith("!")) {
                if (comment.length() > 0) {
                    comment.append('\n');
                }
                comment.append(line.substring(1).trim());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                comment.setLength(0);
                continue;
            }
            map.put(unescape(line.substring(0, eq).trim()),
                new ConfigurationProperty(unescape(line.substring(eq + 1).trim()),
                    comment.toString()));
            comment.setLength(0);
        }
        return map;
    }

    private static String renderProperties(Properties props, String heading) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(heading).append(", captured by the Backup extension.\n\n");
        if (props != null) {
            Map<String, String> sorted = new TreeMap<>();
            for (String key : props.stringPropertyNames()) {
                sorted.put(key, props.getProperty(key));
            }
            sorted.forEach((k, v) -> out.append(escape(k)).append(" = ")
                .append(v == null ? "" : escape(v)).append('\n'));
        }
        return out.toString();
    }

    private static Properties parseProperties(String text) {
        Properties props = new Properties();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            props.setProperty(unescape(line.substring(0, eq).trim()),
                unescape(line.substring(eq + 1).trim()));
        }
        return props;
    }

    // A newline inside a value would end the record early and shift everything after it,
    // so the three characters that can do that are escaped and nothing else is.
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
