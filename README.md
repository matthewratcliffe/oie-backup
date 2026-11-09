# Backup — OIE engine extension

Backs up engine configuration — chosen part by part — and message history into a
single zip archive, on a schedule or on demand, and restores from one. The
archive goes wherever you point it: this engine, a directory, a network share
reached over SMB with its own credentials, Amazon S3 or anything that speaks its
API, Azure Blob Storage, SFTP or FTP. Optionally encrypted before it leaves,
optionally pruned on a retention policy.

**Status: builds, installs, loads, and has taken and restored real backups.**
All 25 Java sources compile against the engine's own jars with `-Werror`; the
cron evaluator, the archive encryption, the AWS request signing and the SMB path
conversion are asserted against fixed vectors at build time; the console helpers
have unit tests. On a running three-node cluster it has captured all thirteen
configuration scopes and 914 real messages, written them to a Samba share with
credentials, encrypted an archive and read it back, restored onto the engine,
pruned to a retention limit, and served a download and an upload. **Four
destinations have not been exercised against real storage** — see
[Remaining](#remaining).

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| Where it runs | Java engine extension | Same reasoning as Git Sync: the console's `server.js` backend only exists when the web administrator runs under Node, and its `/plugin-api` routes are unauthenticated and reachable before login. A backup reaches every controller and holds every credential; it belongs inside the engine. |
| Archive format | A zip, with a manifest | A zip has a central directory, so listing what is inside one does not mean reading a 12 GB file off an SFTP server. Every operator already has a tool for it. And the layout inside is **identical** to Git Sync's and `scripts/oie-config-pull.sh`'s, so a backup's `config/` directory is a configuration repository's working tree, unchanged. |
| What is in it by default | **Everything** | The opposite default from Git Sync, deliberately. A repository is a shared desired state and copying one environment's identity into it would be wrong; a backup is this engine's own record of itself, and an omission is a gap you discover on the day it matters. Per-part selection exists for the other direction — a pre-deployment snapshot, or moving channels between environments without their server name and SMTP host. |
| Messages | Opt-in, and really selectable | Configuration is megabytes; message history is however many gigabytes the engine has accumulated, and every byte of it is clinical content that will be sent to third-party storage. Date range, channels, statuses and attachments are all narrowed at the source. |
| Message export mechanism | The engine's own `exportMessages` / `importMessagesServer` | The same two calls behind the Administrator's Export and Import Messages, writing the same full-message XML. Using them rather than reading `d_mm*` directly is what makes an archive restorable onto a different engine and onto a later version — the serialiser migrates a message forward on the way in. |
| Restore semantics | **Replace or merge, and the operator picks** | "Put it back how it was" and "add back what was lost" are both legitimate and they are not the same operation, so neither is hardcoded. Replace makes the engine match the archive and deletes what the archive does not describe; merge leaves everything else alone. The console defaults to replace, because that is what restoring a backup usually means; the API defaults to merge, because a parameter nobody sent must not delete anything. |
| Replace is gated on a preview | The list of what would go, before it goes | Deleting a channel takes its message history with it and there is no undo, so the confirmation is made against the actual objects — named, with `deployed` and `history` tags — rather than against an idea of them. Changing the selection clears the preview. The same review-then-act shape the Git Sync extension uses for a branch switch. |
| Restore selection | Configuration and messages picked separately, and taken literally | Two lists, because "restore the channels out of this archive but not its million messages" is the common case, not the exotic one. An empty selection means *nothing* — never a helpful fallback to everything — and a restore that would apply nothing is refused rather than reported as a success. An "ok" that changed nothing is indistinguishable, in a history row and in somebody's memory, from one that worked. |
| Messages start unticked | Always, even when the archive has them | Asymmetric on purpose. Not restoring messages you wanted costs a second restore; restoring four million you did not costs a database. |
| Restore ordering | Configuration, then messages; code templates before channels | Neither is the operator's to get wrong. A message cannot be imported into a channel that does not exist yet, and a channel that references a code template should not be applied before it. |
| Scheduling | Wake every minute, re-read the schedule | Not a task that schedules itself for the next firing. The schedule lives in the database and is shared, so it can change on another node; a task holding a precomputed firing time would keep the old one until something restarted it. |
| Cron | Five-field, implemented here | The engine ships Quartz, and the Data Pruner uses it — but what an extension's classloader sees from `server-lib` is a property of how the engine was launched, not of the extension, and a backup that stops being taken because a jar moved is the worst failure this plugin can have. The six-field Quartz form is **refused with a reason**, because reading its seconds as minutes would fire at the wrong time silently. |
| Destinations | One interface, seven implementations, one bundled jar | S3 and Azure over the JDK's `HttpClient` with hand-written request signing; SFTP over the engine's JSch; FTP over the engine's commons-net. The engine *does* ship AWS SDK 2.15.28 — from 2020, in `server-lib/aws` — and depending on it would couple this extension to the engine's library layout for no gain on two documented, stable HTTPS APIs. The one bundled jar is jcifs-ng, for SMB; see below. |
| "Network path" | A directory **or** a share, decided by the path | Both cases are real and they are not the same case. A share the platform has already mounted — a volume in compose, a PVC in Kubernetes, `mount -t cifs` on a host — is a directory and needs no credentials. A NAS that has *not* been mounted, because the engine runs in a container nobody wants to give `CAP_SYS_ADMIN` or because the credentials belong to this backup job rather than to the host, needs its own username and password. A UNC path or an `smb://` URL goes over SMB; anything else is an ordinary directory. One entry in the destination list rather than two, because from the operator's side it is one question and the path they type already answers it. |
| SMB client | jcifs-ng, bundled and checksum-verified | The engine ships it, but in `client-lib` and inside the File connector's own extension directory, not on the shared server classpath — so an extension cannot rely on seeing it, and the first build that relied on it produced `could not locate library` in the engine log and an extension that loaded with a jar missing. The same version is bundled, verified against the SHA-1 Maven Central publishes beside it, which is the arrangement Git Sync uses for JGit. |
| SMB dialect | SMB2 minimum | SMB1 has been off by default in Windows since 2017 and is the protocol behind WannaCry. A NAS old enough to need it should be reached through a mount the operating system makes, where that decision is visible to whoever administers the host rather than buried in a plugin's settings. |
| Large archives | Multipart on S3, block list on Azure | A single PUT caps at 5 GiB, which an engine with a year of messages passes without anybody deciding to. A failed multipart upload is **aborted**, because parts left behind appear in no listing and are billed until a lifecycle rule removes them. |
| Encryption | Optional AES-256-GCM, chunked, no escrow | An archive holds every credential in the configuration map, the SMTP password from the server settings, and the clinical content of everything the engine has processed — and is then handed to storage this engine does not control. SSE-S3 protects against a stolen disk, not a misconfigured bucket policy. Truncation is detected: a cut-off archive does not decrypt to a valid shorter one. |
| Retention | Two limits, and a separate switch to act on them | Setting "keep 7" is not by itself an instruction to delete. Deleting a backup is irreversible and is somebody else's data-retention policy; this carries it out and does not decide it. The console shows exactly which files the current limits select **before** the switch goes on. |
| What retention will touch | Only files matching this plugin's own name pattern | Pointing a backup at `/mnt/archive` that already holds a year of invoices is a configuration mistake. It must not become a data-loss incident. |
| Cluster | A claim in the shared record, no election | `state.runningOn` and `state.lastRun` are written *before* the work starts, so a neighbour waking a second later finds the window taken. Two nodes reading at the same instant can both claim; the cost is one extra archive a second apart, and a real lock would mean a table this extension does not own and a crashed node holding it forever. The claim expires after 12 hours. |
| TLS certificates and keys | Backed up with the TLS Manager extension's own stores; restore **merges by alias** | The key pair store and the trusted certificate store are the two properties TLS Manager keeps in the engine's configuration, so the two extensions share no code and either can be installed alone. The archive then holds **private keys**: encrypt archives that include this scope. A restore adds or replaces aliases and never deletes one, because a private key that was not in the archive cannot be recovered once removed. TLS Manager reloads from the database within about fifteen seconds, so no restart is needed. A TLS Manager in filesystem mode has no such properties and contributes nothing. |
| Users | Backed up, **without passwords** | The engine keeps credentials behind a one-way digest and exposes no way to write them back. A restored account exists and carries its role and cannot be signed in to until somebody sets a password. Rebuilding twenty users and their roles by hand is its own outage, so it is worth capturing — and the restore report says this every time, rather than only in this table. |
| Confirmation | Type the archive name | For restore and for delete. A second click is only a slower way to click. |

## The archive

```
manifest.properties          what this is: when, from which server and environment,
                             which engine version, which scopes and how many of each,
                             which channels' messages and how many
config/
  channels/<name>.xml
  code-templates/libraries.xml
  code-templates/templates/<name>.xml
  channel-groups.xml
  configuration-map.properties
  alerts/<name>.xml
  global-scripts.xml
  server-settings.xml
  administrator-settings.xml
  channel-tags.xml
  resources.xml
  data-pruner.properties
  volume-monitor.properties
  users.xml
  tls-manager/extraKeyStore.p12.b64     TLS Manager key pairs, with private keys
  tls-manager/extraTrustStore.p12.b64   TLS Manager trusted certificates
messages/channels.tsv        channel id, name and count, so ids have names
messages/<channel-id>/1.xml  one file per message, the engine's own export format
```

Channel directories are named by **id**, not by name, because the id is what the
import needs and a name can change between the backup and the restore.
`channels.tsv` carries the names so a person reading the archive is not looking
at a list of UUIDs.

The first thirteen `config/` files are exactly Git Sync's layout, which is also
`scripts/oie-config-pull.sh`'s for the seven they share. `users.xml` is this
plugin's addition. That overlap is the point: a backup can be committed to a
configuration repository, and a checkout can be restored by this plugin.

Files are named from each object's name with a short id suffix, and the full id
kept **inside** the file — so a renamed object gets a renamed file without
changing identity, and the restore is an upsert keyed on id rather than a
duplicate. The suffix exists because two objects can legitimately share a name
and a filesystem cannot hold two files with one.

## Archive names

```
oie-backup-20260919T020000Z.zip
oie-backup-20260919T020000Z-nightly.zip.enc
```

UTC, and lexically sortable, which is what lets retention find the oldest
without asking a destination for modification times it may not keep. The
optional label comes from the settings, so two environments writing into one
bucket stay distinguishable. Anything not matching this pattern is invisible to
this plugin — never listed, never counted, never deleted.

## Encryption format

```
"OIEBACKUP" (9 bytes)   magic
1 byte                  format version, currently 1
1 byte                  key derivation, currently 1 = PBKDF2WithHmacSHA256
4 bytes                 iteration count, big-endian (600,000)
1 byte + n              salt length and salt
1 byte + n              nonce prefix length and prefix
4 bytes                 plaintext chunk size, big-endian (1 MiB)
then, repeated:
  4 bytes               ciphertext length of this chunk, big-endian
  n bytes               AES-256-GCM ciphertext, 16-byte tag included
```

Chunked rather than applied to the whole file, because GCM authenticates
everything it covers before yielding any of it — one 40 GB chunk would mean
holding 40 GB before a byte could be written. Each chunk's nonce is
`prefix || counter`, and each chunk's additional authenticated data is the file
header, the chunk index and a flag saying whether it is the last one. That last
flag is what makes truncation detectable: a file cut short does not decrypt to a
valid shorter archive, which matters because a backup that restores successfully
but is silently missing its last thousand messages is worse than one that
refuses to restore.

The iteration count is read from the file rather than assumed, so raising it
later does not make existing archives unreadable.

**There is no key escrow and no recovery.** The passphrase is stored encrypted
with the engine's own `Encryptor` so scheduled backups can run unattended, which
means an engine that is still running can always read its own archives and an
engine rebuilt from nothing cannot — exactly the situation the passphrase needs
to survive independently of this engine. The console says this in plain words
rather than in a tooltip.

## Restoring

**Plugins → Backup**, scroll to **Archives**, press **List archives**. Then on the
row you want:

1. **Inspect** reads the archive's manifest and shows what is in it — when it was
   taken, from which environment, how many of each configuration object, and
   which channels' messages with their counts. Worth doing first: choosing
   between six backups by filename is how the wrong one gets restored.
2. **Restore…** opens the panel. It fetches and reads the archive, then shows
   two independent lists, both of which you pick from:
   - **Configuration to restore** — a checkbox per part the archive actually
     holds, with its object count, and **All** / **None** / **Channels only**
     buttons. Parts that carry this instance's own identity (server settings,
     Data Pruner, Volume Monitor, users) are marked `instance`.
   - **Messages to restore** — a checkbox per channel in the archive, with its
     message count, and **All** / **None**. **This starts empty.** An archive
     that happens to carry a year of history will not touch it unless you tick
     something, because the expensive direction here is doing more than was
     asked and an import of four million messages cannot be undone.
3. A line above the confirmation says what will happen — *"Will restore 1
   configuration part and no messages."* — and the **Restore** button stays
   disabled until you type the archive's name and have ticked at least one
   thing.

So your case — an archive that includes messages but you only want the channels
— is **Channels only** in the top list, **None** in the bottom one.

### Replace or merge

Below the two lists is the question of what happens to anything on the engine
that the archive does **not** contain, within the parts you selected:

- **Replace — make this engine match the archive.** The default in the console.
  Objects the archive does not describe are removed, so the engine ends up
  holding exactly what the backup held. This deletes channels, **and a deleted
  channel takes its message history with it.** There is no undo.
- **Merge — add and update, remove nothing.** The default over the API, for an
  omitted parameter. The restore adds back what was lost and leaves everything
  else alone.

Replace will not run until you press **Show what would be removed** and the list
appears: every object by name, with a `deployed` tag where it is running and a
`history` tag where removing it destroys data. Changing the selection clears
that list, because a preview computed against different ticked boxes is the
wrong preview. The account performing the restore is never removed.

One rule makes this safe to act on: **an absent directory in the archive means
"this backup does not describe that type", never "delete everything of that
type".** A zip does not store empty directories, so a backup whose alerts were
all deleted and a backup that never captured alerts look identical once
unpacked — and reading the second as the first would offer to delete every alert
on the engine. Ids are read out of the XML with a regex rather than by
deserialising, for the same reason pointing the other way: a file this engine
cannot parse still counts as *present*, because the alternative is a parse
failure quietly becoming a deletion.

Three options sit below:

| | |
| --- | --- |
| **Deploy these channels first** | Only shown when messages are selected. The engine creates a channel's message tables at deploy time and refuses an import into an undeployed channel, so without this an undeployed channel is reported and skipped. Deploying also **starts** the channel. Off by default. |
| **Take a configuration backup first** | On by default. Configuration only, kept on this engine. |
| **Redeploy every channel afterwards** | Off by default. Restored channel definitions sit in the database while the engine keeps running the deployed ones until something redeploys them. |

### Over the API

```
POST /api/backup/restore
<map>
  <entry><string>name</string><string>oie-backup-20260919T020000Z.zip</string></entry>
  <entry><string>scope</string><string>channels,code-templates</string></entry>
  <entry><string>channels</string><string>none</string></entry>
</map>
```

`scope` and `channels` each distinguish **three** cases, and the distinction is
load-bearing:

| | `scope` | `channels` |
| --- | --- | --- |
| key omitted | every configuration part in the archive | every channel in the archive |
| `none` | no configuration | no messages |
| a comma-separated list | exactly those | exactly those |

A list is taken **literally**: an empty one restores nothing, never everything.
That is not pedantry — an empty string is what a form posts when every box has
been unticked, and reading it as "all of them" would overwrite the engine's
channels, alerts and user accounts while claiming to have honoured a selection.
A restore that selects nothing at all is refused rather than reported as a
successful restore of nothing.

Add `<entry><string>replace</string><string>true</string></entry>` to make the
engine match the archive rather than merge into it. `POST
/api/backup/restore/preview` with `name` and `scope` answers what that would
remove, without removing it.

## The network path, with or without credentials

One destination, two behaviours, chosen by what the path looks like:

```
/mnt/backups                    an ordinary directory, written as the engine's own user
D:\backups                      the same on Windows
\\nas\backups\oie               a share, reached over SMB
//nas/backups/oie               the same
smb://nas/backups/oie           the same
```

For the three share forms the console offers a username, a password and a domain
or workgroup, all optional — leaving the username empty connects anonymously,
which is what a share with guest access wants. The password is encrypted with
the engine's own key before it is stored and is never returned by the API.

The domain is its own field rather than split off a `DOMAIN` + backslash +
username string, because a username containing a backslash is also how a local
account on a NAS is spelled, and guessing between the two fails with an
authentication error that gives no clue which reading was wrong.

Credentials set against a path that is *not* a share are reported as a problem
rather than ignored. They would do nothing — an ordinary directory is reached as
the engine's process user — and somebody who filled them in believes the
opposite, which is a belief worth correcting before the first scheduled run
rather than after it.

`Test destination` writes a marker, lists the directory and removes the marker,
and reports the free space. That last part is how a mount that did not happen
shows up: a "network path" sitting on the same 30 GB container overlay as
everything else is visible at a glance, and a real share is not.

## Building

```
./plugins/oie-backup/build.sh
```

No local JDK or Maven. It compiles in a container and takes the engine jars
straight out of `oie/engine:4.6.0`, so the extension is always compiled against
exactly the engine it will run on.

One dependency is bundled: `jcifs-ng`, plus the `slf4j-api` it needs, for the
SMB half of the network-path destination. Both come from Maven Central and are
verified against the SHA-1 each artifact publishes beside it — they are loaded
into the engine's JVM, so an artifact changing under us would be arbitrary code
execution inside the engine. Everything else uses something the engine already
has.

`plugin.xml` is checked for leftover `@NAME@` placeholders before packaging. A
missed substitution does not fail the build on its own: `MirthLauncher` logs
`could not locate library` and carries on, and the extension then loads with a
jar missing and fails later, at the one destination that needed it. That
happened once during development, which is why the check exists.

`plugin.xml` is generated from `plugin.xml.in` with the versions stamped in, and
is parsed before packaging — `MirthLauncher` reads it before the engine starts
and, on a parse error, logs one line and carries on without the extension, after
which everything downstream looks like it simply is not installed. (The parse
check earned its keep immediately: an XML comment containing `--` is illegal, and
this repository's house style uses `--` in prose constantly.)

## Verified

### At build time

Asserted by `build.sh` or by `node --test`, and fails the build or the test run
if it stops holding.

- **Compiles clean** against the engine's own jars with `-Xlint:all -Werror`,
  25 source files.
- **Cron**, 14 fixed vectors (`Cron.selfTest`): `0 2 * * *` from 03:00 is
  tomorrow at 02:00 and from 01:30 is today at 02:00; `*/15 * * * *` from 10:07
  is 10:15; a job that has just fired does not fire again on the spot;
  `30 1 * * SUN` finds the Sunday; `0 0 1 * *` finds the first; Vixie's or-rule
  holds for `0 3 1 * MON` in both directions; `0 0 29 2 *` reaches 2028;
  `5/20 * * * *` runs to the end of its range; `0 0 31 2 *` never fires; and the
  six-field Quartz form, hour 24, four fields and an unknown day name are each
  refused.
- **Archive encryption**, round trip and both failure modes
  (`ArchiveCipher.selfTest`): a 2 MiB-plus file that does not end on a chunk
  boundary round trips byte for byte; a wrong passphrase raises
  `BadPassphraseException` specifically; a truncated archive is rejected **and
  is not reported as a wrong passphrase**; a single flipped bit is rejected.
- **AWS SigV4** (`AwsV4Signer.selfTest`): the documented signing-key derivation
  vector from the AWS documentation matches; a valueless parameter canonicalises
  as `uploads=` with the trailing equals; parameters sort by name; a space
  encodes as `%20`; key encoding keeps `/` and encodes `*`; the empty-payload
  hash constant is correct.
- **SMB paths** (`SmbDestination.selfTest`): all three spellings of a share
  convert to the same `smb://host/share/dir/` with its trailing slash; a local
  path and a Windows drive letter are not mistaken for shares; a server with no
  share and a traversing path are refused.
- **`plugin.xml` parses**, and carries no unsubstituted placeholders, both
  checked before the zip is built.
- **Console helpers**, 5 tests (`test/rows.test.mjs`): XStream map and list
  decoding including the single-element list XStream collapses; XML escaping on
  the way out; archive rows with a missing timestamp; a history row written by an
  older version padding rather than throwing; and multi-line settings — an SFTP
  private key and `known_hosts` — round-tripping through the row encoding,
  including a value containing a backslash.
- **`plugin.js` is a valid ES module** and exports `register`.
- **Packages**: `dist/backup-0.1.0.zip`.

### On a running engine

Against the three-node cluster in this repository (`compose.yaml` +
`compose.cluster.yaml`), with a `dperson/samba` container on the same network
standing in for a NAS.

- **Extension loads**: `backup: working directory /opt/engine/appdata/backup`,
  `backup: installed, schedule switched off; backups run on request only`. No
  `could not locate library` lines — the bundled jars resolve.
- **REST API answers**: `GET /api/backup/status` returns the settings, the
  thirteen scopes, the six destination types, the channel list and an empty
  history on a fresh install.
- **Configuration capture, all thirteen scopes**, against a real engine:
  2 channels, 2 configuration-map entries, 4 global scripts, server settings,
  administrator settings, resources, 9 Data Pruner properties, 3 Volume Monitor
  properties (from this repository's other extension) and 2 users. The archive's
  `config/` directory is byte-for-byte the layout Git Sync uses.
- **Message capture**: 914 real messages from 2 channels, 2.1 MB archive.
- **SMB destination with credentials**: `Test destination` →
  `Connected to the share, wrote and removed a probe file. 0 archive(s) already
  there. 741 GB free.` Backup written, confirmed present on the Samba container's
  own filesystem, listed back with size and modification time.
- **SMB failure paths**: an unreachable host answers `could not reach
  //nas.invalid/backups/oie: Failed to connect to server. Check the server name
  resolves from inside the engine container, and that TCP 445 is reachable from
  it.`
- **Encryption round trip**: an encrypted backup written to the share (file
  begins `OIEBACKUP` on the Samba container), read back and its manifest
  decoded; a wrong passphrase answers `the passphrase does not decrypt this
  archive` with `needsPassphrase`.
- **Restore**: from the encrypted archive on the share, config-only, with a
  safety backup first — twelve scopes applied through the engine's controllers,
  no problems, and the three notes rendered (safety archive name, server-settings
  warning, not-redeployed warning). Channels intact afterwards.
- **Restore with a scope subset**: `scope=channels,code-templates` applied only
  those two.
- **Selective restore, the case this is really for**: from an archive holding
  all thirteen configuration parts *and* 914 messages, `scope=channels` +
  `channels=none` restored 2 channels and touched no message history.
- **Empty selection refused**: `scope=none` + `channels=none` answers
  `nothing was selected to restore` rather than falling back to everything.
- **Per-channel message restore**: `scope=none` +
  `channels=<one channel id>` imported that channel's 260 messages and left the
  other channel's 654 exactly as they were.
- **The undeployed-channel case**: without the deploy option, an undeployed
  channel is reported in one line naming the channel and the fix, and skipped.
  With `deployForMessages=true` the channel is deployed, the 260 messages land,
  and the report says the channel is now running and was not before.
- **Retention**: with `keepCount=2` and pruning off, the archive list marked the
  two oldest of four; with pruning on, the next run removed three and left the
  newest two.
- **Download**: `GET /api/backup/download?name=…` → `200`,
  `Content-Type: application/octet-stream`, `Content-Disposition: attachment;
  filename="…"`, correct `Content-Length`, and a body starting `PK`. The
  `Response` + `StreamingOutput` shape survives the engine's Jersey
  configuration.
- **Upload**: a real archive accepted and reported back with its size; a file
  named `not-an-archive.zip` refused with the reason. Restore-from-upload then
  worked against it.

### In the console

Driven in a real browser against the running cluster.

- **The page loads and registers**: `[plugins] backup v0.1.0 registered`, the
  **Backup** item appears under *Plugins*, and every panel renders.
- **The archive list is fetched on mount** and shows the destination in the
  panel header, with the cluster caveat when the destination is this engine.
- **It refreshes by itself**: pressing *Back up now* produced
  `Backed up to oie-backup-20260919T044219Z.zip (10 KB)` and the archive
  appeared in the list underneath — `1 archive, listed just now` — with its
  Download / Inspect / Restore / Delete buttons, without touching *Refresh*.
- **The restore picker**, on an archive holding 13 configuration parts and
  1,174 messages: thirteen configuration checkboxes each with their object
  count and the `instance` markers, All / None / Channels only above them; two
  channels listed with 520 and 654 messages, All / None above them, **starting
  unticked**; and the line *"No messages selected — 2 channels of history in
  this archive will not be touched."*
- **The selection reaches the summary**: pressing *Channels only* changed
  *"Will restore 13 configuration parts and no messages"* to *"Will restore 1
  configuration part and no messages"* — the exact case this is for.
- **Inspect and Restore open where you can see them.** Both panels render near
  the top of the page while their buttons are in the archive list at the bottom,
  so they used to appear more than a screen above the viewport and look like
  they had done nothing: the Close button measured at `y: -1290`. With the
  reveal they measure at `y: 164`, in the viewport.
- **The replace/merge picker** renders with its preview gate: *"Required before
  a replacing restore can run"*, and after pressing *Show what would be removed*,
  *"Nothing would be removed: this engine already holds only what the archive
  describes."*
- **Replace, end to end**: with an extra channel on the engine that the archive
  did not contain, the preview named exactly that channel and tagged it
  `history`; a merge restore left it in place; a replacing restore reported
  `channelsRemoved 1` and the engine was left holding exactly the archive's two
  channels.
- **The absent-directory rule holds**: previewing `scope=alerts` against an
  archive with no `alerts/` directory resolved to no scopes and no removals, so
  an archive that never captured alerts cannot be read as "delete every alert".

## Remaining

1. **Four destinations are still untested against real storage.** S3, Azure,
   SFTP and FTP compile, and their signing and path handling are asserted at
   build time, but no archive has actually been written to any of them. S3 is
   the highest-value one to try next and the easiest — MinIO in a container on
   the same network, with a custom endpoint and path-style addressing, exercises
   the same code path Amazon does.
2. **Multipart and block uploads have not run.** The S3 multipart path and the
   Azure block-list path trigger above 256 MB, which no test archive has reached.
   They are the most intricate code in the extension and the least exercised.
   Forcing the threshold down for one run against MinIO would close that.
3. **No round-trip test.** Capture from an engine, restore onto an empty one,
   capture again, assert the trees match. Cheap to write and it would catch
   serialiser drift — the same gap Git Sync has. The manual restore above
   confirms the mechanism, not the fidelity.
4. **"Keep on this engine" is per-node, and that shows in a cluster.** Every
   verification above went through the load balancer, and the archives landed on
   whichever node answered — a download for an archive held by a *different*
   node answers 404. That is correct behaviour for a local directory and it is a
   good argument against using this destination for anything but a trial; the
   console should say so more loudly than it does.
5. **Retention keys off the archive name.** An archive whose name this plugin did
   not generate is ignored entirely, which is the safe direction, but it means an
   archive renamed by hand stops being managed. Worth a line in the console when
   a directory holds files that look close to the pattern but do not match it.
6. **The dry run only covers removals.** `/restore/preview` says what a
   replacing restore would *delete*, which is the irreversible half. It does not
   say which existing objects would be *overwritten* and how they differ — Git
   Sync shows a per-file diff for that, and it is the most useful thing to add
   here next.
7. **The safety backup is configuration only, and goes only to this engine.**
   Right for the case it is for — a net that takes hours is a net that gets
   skipped — but an operator restoring onto an engine whose local disk is the
   problem gets less than they might expect. The console says where it went.
8. **The destination form's show-when logic is unexercised in a browser.** The
   page renders and has been driven for real — see below — but the
   per-destination fields have only been reasoned about: the S3 block, the SFTP
   password/key switch, and the SMB credentials appearing once the path is a
   share.

## Operating notes

**Turn the schedule on last.** Configure, test the destination, take one by
hand, look at what landed — and only then enable the schedule. The first
unattended run should not be the first run that has ever been tried. The
console's own copy says this next to the switch.

**"Keep on this engine" is not a backup.** It is on the machine being backed up.
It is the right destination for trying the plugin out and for the pre-restore
safety copy, and the wrong one for anything else. In a cluster it is also
*per-node*: a request that reaches one engine writes the archive there, and a
download request that the load balancer sends to a different engine answers 404.
Every real destination is shared between the nodes; this one is not.

**Updating the web UI needs a hard reload.** The console imports a plugin's
`web/plugin.js` with no cache-buster, so a browser that has already loaded the
old module keeps using it — a normal navigation is not enough. After installing
a new build, hard-reload the console (Ctrl+Shift+R).

**Secrets never come back.** The API answers `set` or empty for a password, an
access key or a passphrase, never the value. A secret field therefore always
renders empty, submitting it empty means "leave it alone", and clearing one
takes the **Clear** button. This is also why editing a bucket name does not
require pasting the key back in.

**Styling comes from the shell.** The page renders with `.panel`,
`.panel-header`, `.panel-body`, `.field`, `.hint`, `.btn`, `.btn-primary`,
`.btn-danger`, `.tag`, `.kv`, `.mono` and `.form-grid`, so it inherits the app's
spacing, borders and both themes. Inline style is used only where those classes
do not reach, and then in terms of the app's CSS variables (`var(--err)`,
`var(--line)`) rather than fixed colours. The view root sets `display: block`
and `overflow-y: auto` inline to override `.view`, which is a flex column with
`overflow-y: hidden` and would otherwise make the page unable to scroll at all.

**Restoring messages adds, it does not replace.** Each message gets a new
message id on this engine and keeps its original as an import id, so restoring
the same archive twice makes two copies. The console says so before the
confirmation and the report says how many went in.

**A channel must be deployed before its messages can be imported.** The engine
creates a channel's message tables at deploy time and refuses an import into a
channel it has not deployed. The restore checks this per channel before
importing rather than letting it fail per message — on a channel with four
million of them, the engine's own error is four million copies of the same
sentence. The fix is the **Deploy these channels first** option, which is off by
default because deploying a channel starts it listening, polling and sending,
and on a freshly restored production configuration that is a decision somebody
should make on purpose.

**Restored channels are not deployed.** A deployed channel keeps running the
version it was deployed with until it is redeployed. The restore offers to
redeploy everything and defaults to not doing it, because redeploying every
channel on a production engine is its own decision.

## Settings reference

Stored in the `configuration` table under the group `Backup`, so they survive
this image's rebuild of `conf/` on every boot and are shared by every node
against the same database.

| Key | |
| --- | --- |
| `enabled` | whether the schedule runs at all |
| `label` | optional suffix in the archive name |
| `schedule.kind` | `off`, `interval`, `daily`, `weekly`, `monthly`, `cron` |
| `schedule.intervalMinutes` | for `interval`; at least 15 |
| `schedule.timeOfDay` | `HH:MM`, for `daily`/`weekly`/`monthly` |
| `schedule.dayOfWeek` | 1 = Monday .. 7 = Sunday |
| `schedule.dayOfMonth` | 1–28; see below |
| `schedule.cron` | five-field expression |
| `schedule.zone` | an IANA zone; defaults to the JVM's |
| `config.included`, `config.scope` | whether, and which parts |
| `messages.included` | whether |
| `messages.channels` | channel ids, empty for all |
| `messages.days` | 0 for all history |
| `messages.statuses` | empty for all |
| `messages.attachments` | |
| `destination` | `keep`, `local`, `s3`, `azure`, `sftp`, `ftp` |
| `dest.local.path` | a directory, or `\\server\share\dir` / `smb://server/share/dir` |
| `dest.local.username`, `dest.local.secret`, `dest.local.domain` | optional, and used only when the path is a share |
| `dest.<type>.<field>` | per destination; see `Destinations` for the field names |
| `encrypt`, `passphrase` | |
| `retention.keepCount`, `retention.keepDays`, `retention.prune` | |

Destination settings are namespaced by type, so switching to SFTP to try
something and switching back does not lose the S3 configuration.

`schedule.dayOfMonth` stops at 28 deliberately. A monthly backup set for the
31st would silently skip February, April, June, September and November — seven
months a year with no backup and nothing saying so. An operator who genuinely
wants the last day of the month has cron, and that is a decision to make
deliberately rather than to inherit from a dropdown.
