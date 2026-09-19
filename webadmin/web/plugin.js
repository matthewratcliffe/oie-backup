/*
 * Backup console UI.
 *
 * One page, six panels, in the order somebody setting this up needs them:
 * what to back up, when, where to, how it is protected, how long it is kept,
 * and what is already there. Plus the history, because the only question
 * anybody asks of a backup system between incidents is "is it actually
 * running?".
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 *
 * Two things in here are load-bearing and easy to mistake for style:
 *
 *   1. SECRETS ARE NEVER RECEIVED. The API answers "set" or "" for a password,
 *      an access key or a passphrase, never the value. So a secret field always
 *      renders empty, an empty submission means "leave it alone", and clearing
 *      one takes an explicit button that sends a sentinel. Rendering a stored
 *      secret into the DOM -- even as dots -- would put it in the page source,
 *      in a screenshot and in a browser's autofill.
 *
 *   2. THE DESTRUCTIVE ACTIONS ARM THEMSELVES. Restore and delete both gate on
 *      typing something exact, not on a second click, because a second click is
 *      only a slower way to click. The restore confirmation names the archive
 *      and lists what will be written before it accepts anything.
 */

const API = '/api/backup';

/* While a backup or restore is running, this is how often the page asks what
 * phase it is in. A backup can run for hours; three seconds is responsive
 * without being a load on an engine that is busy reading its own message
 * tables. */
const BUSY_POLL_MS = 3000;

/* Fields of each row type, in the order the Java side writes them. */
const SETTING_FIELDS = ['key', 'value'];
const SCOPE_FIELDS = ['token', 'label', 'portable'];
const DEST_TYPE_FIELDS = ['token', 'label', 'network'];
const ARCHIVE_FIELDS = ['name', 'bytes', 'size', 'modified', 'encrypted'];
const HISTORY_FIELDS = ['startedAt', 'finishedAt', 'trigger', 'outcome', 'archive',
    'bytes', 'destination', 'summary', 'problems'];
const CHANNEL_FIELDS = ['id', 'name'];
const MESSAGE_CHANNEL_FIELDS = ['id', 'name', 'count'];
const REMOVAL_FIELDS = ['token', 'label', 'id', 'name', 'deployed', 'destroys'];
const COUNT_FIELDS = ['token', 'label', 'count'];

/* The sentinel BackupSettings.CLEAR_SECRET expects. Printable ASCII, because a
 * control character cannot travel through XML. */
const CLEAR_SECRET = '--oie-backup-clear-this-secret--';

const STATUSES = ['RECEIVED', 'FILTERED', 'TRANSFORMED', 'SENT', 'QUEUED', 'ERROR',
    'PENDING'];

const DAYS = [
    [1, 'Monday'], [2, 'Tuesday'], [3, 'Wednesday'], [4, 'Thursday'],
    [5, 'Friday'], [6, 'Saturday'], [7, 'Sunday'],
];

/*
 * Whether a network path is a share, and therefore whether to offer credentials.
 *
 * The same three spellings SmbDestination.looksLikeShare accepts, because all
 * three are what people actually type. Keeping the page in step with the server
 * matters more here than in most places: if the page offered credentials for a
 * path the server would treat as a plain directory, they would be saved, stored
 * encrypted, and silently never used.
 */
function isShare(values) {
    const path = (values.path || '').trim();
    return path.startsWith('\\\\') || path.startsWith('//')
        || path.toLowerCase().startsWith('smb://');
}

/*
 * The destination forms, as data.
 *
 * One table rather than six components. Each destination differs only in which
 * named strings it needs, and the server already treats them as named strings
 * (see DestinationConfig) -- so describing them here keeps the page and the
 * server agreeing about field names by construction, and adding a seventh
 * destination is a row here rather than a new component.
 *
 * `secret: true` marks a field the API will never send back.
 */
const DEST_FIELDS = {
    keep: [],
    local: [
        { key: 'path', label: 'Directory or network path', placeholder: '/mnt/backups',
          required: true,
          hint: 'A path the engine can write to, or a share: '
              + '\\\\nas\\backups\\oie, //nas/backups/oie or smb://nas/backups/oie. '
              + 'A share is reached over SMB with the credentials below; anything else is '
              + 'an ordinary directory, reached as the engine’s own user. Test the '
              + 'destination afterwards — it reports the free space, which is how a '
              + 'mount that did not happen shows up.' },
        { key: 'username', label: 'Username', showWhen: isShare,
          hint: 'Optional. Leave it empty for a share with guest access.' },
        { key: 'secret', label: 'Password', secret: true, showWhen: isShare },
        { key: 'domain', label: 'Domain or workgroup', showWhen: isShare,
          hint: 'Optional. Kept separate rather than parsed out of DOMAIN\\user, because '
              + 'a username containing a backslash is also how a local account on a NAS '
              + 'is spelled — and guessing between the two fails with an '
              + 'authentication error that gives no clue which it was.' },
    ],
    s3: [
        { key: 'bucket', label: 'Bucket', required: true },
        { key: 'prefix', label: 'Prefix', placeholder: 'oie/production',
          hint: 'Optional. Only archives directly under it are listed, so a bucket '
              + 'shared with something else stays safe from retention.' },
        { key: 'region', label: 'Region', placeholder: 'ap-southeast-2', required: true,
          hint: 'Part of the signature. A bucket signed for the wrong region fails '
              + 'exactly like a wrong secret key.' },
        { key: 'endpoint', label: 'Endpoint',
          placeholder: 'leave empty for Amazon S3',
          hint: 'For DigitalOcean Spaces, MinIO, Wasabi, Ceph or R2. For example '
              + 'https://syd1.digitaloceanspaces.com.' },
        { key: 'pathStyle', label: 'Path-style addressing', type: 'bool',
          hint: 'On by default for a custom endpoint, because virtual-hosted addressing '
              + 'needs wildcard DNS and a wildcard certificate. Off for Amazon.' },
        { key: 'accessKeyId', label: 'Access key id', required: true },
        { key: 'secret', label: 'Secret access key', secret: true, required: true },
        { key: 'storageClass', label: 'Storage class', type: 'select',
          options: [['', 'Default (Standard)'], ['STANDARD_IA', 'Standard-IA'],
                    ['INTELLIGENT_TIERING', 'Intelligent-Tiering'],
                    ['GLACIER_IR', 'Glacier Instant Retrieval'],
                    ['DEEP_ARCHIVE', 'Glacier Deep Archive']],
          hint: 'Deep Archive is cheap to hold and slow to read -- hours. That is the '
              + 'wrong trade for the backup you restore from and the right one for the '
              + 'copy you keep for seven years.' },
        { key: 'serverSideEncryption', label: 'Server-side encryption', type: 'select',
          options: [['', 'None'], ['AES256', 'SSE-S3 (AES256)'], ['aws:kms', 'SSE-KMS']] },
        { key: 'kmsKeyId', label: 'KMS key id', showWhen: (v) => v.serverSideEncryption === 'aws:kms' },
    ],
    azure: [
        { key: 'account', label: 'Storage account', required: true },
        { key: 'container', label: 'Container', required: true },
        { key: 'prefix', label: 'Prefix' },
        { key: 'azureAuth', label: 'Authenticate with', type: 'select',
          options: [['key', 'Account key'], ['sas', 'Shared access signature']],
          hint: 'A SAS is the safer choice: it can be scoped to this container, given '
              + 'create and write without delete, and given an expiry. An account key is '
              + 'every container in the account, forever.' },
        { key: 'secret', label: 'Account key', secret: true,
          showWhen: (v) => (v.azureAuth || 'key') !== 'sas' },
        { key: 'sasToken', label: 'SAS token', secret: true,
          showWhen: (v) => v.azureAuth === 'sas',
          hint: 'With or without the leading question mark. Needs create, write, read and '
              + 'list; add delete only if retention should prune here.' },
        { key: 'endpoint', label: 'Endpoint',
          placeholder: 'leave empty for blob.core.windows.net',
          hint: 'For a sovereign cloud, or for Azurite.' },
    ],
    sftp: [
        { key: 'host', label: 'Host', required: true },
        { key: 'port', label: 'Port', type: 'number', placeholder: '22' },
        { key: 'username', label: 'Username', required: true },
        { key: 'path', label: 'Remote directory', placeholder: 'backups/oie',
          hint: 'Relative to the login directory unless it starts from the root. It is '
              + 'not created for you: creating a tree from a typo is how backups end up '
              + 'somewhere nobody looks.' },
        { key: 'authMethod', label: 'Authenticate with', type: 'select',
          options: [['password', 'Password'], ['key', 'Private key']] },
        { key: 'secret', label: 'Password', secret: true,
          showWhen: (v) => (v.authMethod || 'password') !== 'key' },
        { key: 'privateKey', label: 'Private key', type: 'textarea', secret: true,
          showWhen: (v) => v.authMethod === 'key',
          placeholder: '-----BEGIN OPENSSH PRIVATE KEY-----' },
        { key: 'passphrase', label: 'Key passphrase', secret: true,
          showWhen: (v) => v.authMethod === 'key' },
        { key: 'hostKeyPolicy', label: 'Host key', type: 'select',
          options: [['known_hosts', 'Check against known_hosts'],
                    ['trust_any', 'Trust any key (not verified)']],
          hint: 'SSH has no certificate authorities, so the only real check is against a '
              + 'key you already hold. Trusting any key encrypts the transfer and proves '
              + 'nothing about who receives it -- and what is being sent is a copy of this '
              + 'engine.' },
        { key: 'knownHosts', label: 'known_hosts lines', type: 'textarea',
          showWhen: (v) => (v.hostKeyPolicy || 'known_hosts') !== 'trust_any',
          placeholder: 'backup.example.org ssh-ed25519 AAAAC3Nz...',
          hint: 'Run ssh-keyscan against the server and paste its output.' },
    ],
    ftp: [
        { key: 'host', label: 'Host', required: true },
        { key: 'port', label: 'Port', type: 'number', placeholder: '21, or 990 implicit' },
        { key: 'username', label: 'Username', required: true },
        { key: 'secret', label: 'Password', secret: true, required: true },
        { key: 'path', label: 'Remote directory' },
        { key: 'ftps', label: 'TLS', type: 'select',
          options: [['none', 'None -- plain FTP'], ['explicit', 'FTPS, explicit (AUTH TLS)'],
                    ['implicit', 'FTPS, implicit']],
          hint: 'Plain FTP sends the password and the archive in clear. If the far end '
              + 'speaks nothing else, turn on archive encryption below -- an encrypted '
              + 'archive over plain FTP exposes its name and its size and nothing else.' },
        { key: 'passive', label: 'Passive mode', type: 'bool',
          hint: 'On by default. FTP uses a second connection for data, and passive is the '
              + 'mode that survives the engine being behind NAT -- which, in a container, '
              + 'it always is.' },
    ],
};

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object. Same reason the shell scripts in this repo parse XML.
 */
function decode(node) {
    if (node === null || typeof node !== 'object') return node;
    if (Array.isArray(node)) return node.map(decode);

    const keys = Object.keys(node);

    const mapKey = keys.find(k => k === 'map' || k.endsWith('-map'));
    if (mapKey && keys.length === 1) {
        const inner = node[mapKey];
        if (inner === null) return {};
        let entries = inner.entry;
        if (entries == null) return {};
        if (!Array.isArray(entries)) entries = [entries];
        const out = {};
        for (const entry of entries) {
            const [k, v] = decodeEntry(entry);
            out[k] = v;
        }
        return out;
    }

    const listKey = keys.find(k => k === 'list' || k === 'set'
        || /(^|[.$_-])(list|set|collection)/i.test(k));
    if (listKey && keys.length === 1) {
        const inner = node[listKey];
        if (inner === null) return [];
        const elemKey = Object.keys(inner).find(k => !k.startsWith('@'));
        if (elemKey === undefined) return [];
        const items = inner[elemKey];
        if (items !== null && typeof items === 'object' && !Array.isArray(items)) {
            return [];
        }
        return (Array.isArray(items) ? items : [items]).map(decode);
    }

    const SCALARS = new Set(['string', 'boolean', 'int', 'long', 'double', 'float',
        'short', 'byte', 'char', 'big-decimal', 'big-int']);
    if (keys.length === 1 && SCALARS.has(keys[0])) {
        const v = node[keys[0]];
        return Array.isArray(v) ? v.map(decode) : decode(v);
    }

    const out = {};
    for (const k of keys) out[k] = decode(node[k]);
    return out;
}

function decodeEntry(entry) {
    if (entry === null || typeof entry !== 'object') return [String(entry), null];
    const props = Object.keys(entry);
    if (props.length === 1 && Array.isArray(entry[props[0]])) {
        const arr = entry[props[0]];
        return [String(arr[0]), arr.length > 1 ? decode(arr[1]) : null];
    }
    const keyVal = entry[props[0]];
    const key = Array.isArray(keyVal) ? String(keyVal[0]) : String(keyVal);
    if (props.length < 2) return [key, null];
    const valueName = props[1];
    return [key, decode({ [valueName]: entry[valueName] })];
}

/*
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped. A plain
 * JSON object body gives a 500; the XML map gives a 200.
 */
function encodeMap(obj) {
    const esc = v => String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    const entries = Object.keys(obj)
        .filter(k => obj[k] !== undefined && obj[k] !== null)
        .map(k => `<entry><string>${esc(k)}</string><string>${esc(obj[k])}</string></entry>`)
        .join('');
    return `<map>${entries}</map>`;
}

/* Rows arrive tab-separated; see Tsv.java for why they are not nested objects. */
function rows(list, names) {
    return (list || []).map(line => {
        const parts = String(line).split('\t');
        const out = {};
        names.forEach((name, i) => { out[name] = parts[i] === undefined ? '' : parts[i]; });
        return out;
    });
}

/* The inverse of BackupSettings.escapeLines: a private key and known_hosts are
 * multi-line, and the row encoding cannot carry a newline. */
function unescapeLines(value) {
    let out = '';
    for (let i = 0; i < value.length; i++) {
        if (value[i] === '\\' && i + 1 < value.length) {
            const next = value[++i];
            out += next === 'n' ? '\n' : next === '\\' ? '\\' : next;
        } else {
            out += value[i];
        }
    }
    return out;
}

async function call(method, path, body) {
    const headers = {
        Accept: 'application/json',
        'X-Requested-With': 'oie-webadmin',
    };
    if (body !== undefined) headers['Content-Type'] = 'application/xml';

    const res = await fetch(API + path, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : encodeMap(body),
    });

    let payload = null;
    const text = await res.text();
    if (text) {
        try {
            payload = decode(JSON.parse(text));
        } catch (err) {
            payload = { error: text };
        }
    }
    if (!res.ok) {
        const error = new Error((payload && (payload.error || payload.message))
            || `HTTP ${res.status}`);
        error.status = res.status;
        throw error;
    }
    return payload || {};
}

function settingsOf(payload) {
    const out = {};
    rows(payload && payload.settings, SETTING_FIELDS)
        .forEach(row => { out[row.key] = unescapeLines(row.value || ''); });
    return out;
}

/* "1 channel", "2 channels". Worth the four lines: the line this feeds is the
 * last thing read before a destructive confirmation, and "1 configuration
 * part(s)" reads like the page is guessing. */
function plural(n, noun) {
    return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

function when(iso) {
    if (!iso) return '—';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleString();
}

function ago(iso) {
    if (!iso) return '';
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return '';
    const seconds = Math.round((Date.now() - then) / 1000);
    const future = seconds < 0;
    const n = Math.abs(seconds);
    const units = [[86400, 'day'], [3600, 'hour'], [60, 'minute']];
    for (const [size, name] of units) {
        if (n >= size) {
            const count = Math.round(n / size);
            return `${future ? 'in ' : ''}${count} ${name}${count === 1 ? '' : 's'}${future ? '' : ' ago'}`;
        }
    }
    return future ? 'in under a minute' : 'just now';
}

// ----------------------------------------------------------------------

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    // ------------------------------------------------------------------
    // Presentation, in the shell's own classes and CSS variables so the page
    // follows the app's spacing, borders and both themes.
    // ------------------------------------------------------------------

    /*
     * .view is a flex column with overflow-y: hidden, so child sections
     * flex-shrink to fit the viewport instead of overflowing -- scrollHeight
     * ends up equal to clientHeight and the page cannot scroll at all. The
     * view root therefore overrides the class inline.
     */
    const viewStyle = {
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    };

    /*
     * Scrolls a panel into view when it first mounts.
     *
     * The restore, inspect and delete panels render near the top of the page while the
     * buttons that open them are in the archive list at the bottom of it. Without this the
     * panel appears more than a screen above the viewport, and pressing Inspect or Restore
     * looks like it does nothing at all -- which is exactly how it was reported. A module
     * -level callback has a stable identity, so React attaches it once on mount and
     * detaches on unmount rather than re-running it on every keystroke in the panel.
     */
    function scrollIntoView(node) {
        if (!node) return;
        try {
            node.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (err) {
            // Any browser that dislikes the options object still gets the jump.
            node.scrollIntoView();
        }
    }

    /**
     * A panel. `opts.reveal` scrolls it into view when it appears, for the ones that open
     * in response to a button somewhere else on the page.
     */
    function Panel(title, children, right, key, opts) {
        const o = opts || {};
        return h('div', {
            key,
            className: 'panel',
            style: { marginBottom: '11px' },
            ref: o.reveal ? scrollIntoView : undefined,
        }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                right || null,
            ]) : null,
            h('div', { key: 'b', className: 'panel-body' }, children),
        ]);
    }

    function Button(label, onClick, opts) {
        const o = opts || {};
        const classes = ['btn'];
        if (o.primary) classes.push('btn-primary');
        if (o.danger) classes.push('btn-danger');
        return h('button', {
            key: o.key,
            type: 'button',
            className: classes.join(' '),
            disabled: !!o.disabled,
            title: o.title || '',
            onClick,
            style: { marginRight: '6px', marginTop: o.stacked ? '6px' : 0 },
        }, label);
    }

    function Banner(kind, children, key) {
        const token = { error: '--err', warn: '--warn', ok: '--ok', info: '--accent' }[kind]
            || '--accent';
        return h('div', {
            key,
            style: {
                border: `1px solid var(${token})`,
                borderLeftWidth: '3px',
                borderRadius: '4px',
                padding: '8px 10px',
                marginBottom: '10px',
                fontSize: '0.9em',
            },
        }, children);
    }

    function Hint(text, key) {
        return text ? h('div', { key, className: 'hint',
            style: { marginTop: '3px', maxWidth: '62ch' } }, text) : null;
    }

    function Field(label, control, hint, key) {
        return h('div', { key, className: 'field', style: { marginBottom: '10px' } }, [
            h('label', { key: 'l', style: { display: 'block', marginBottom: '3px' } }, label),
            control,
            Hint(hint, 'h'),
        ]);
    }

    function Rule(key) {
        return h('div', { key, style: {
            borderTop: '1px solid var(--line)', margin: '12px 0' } });
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    function BackupView() {
        const [status, setStatus] = React.useState(null);
        const [form, setForm] = React.useState({});
        const [secrets, setSecrets] = React.useState({});
        const [busy, setBusy] = React.useState(false);
        const [message, setMessage] = React.useState(null);
        const [archives, setArchives] = React.useState(null);
        const [listing, setListing] = React.useState(false);
        const [inspected, setInspected] = React.useState(null);
        const [restoreFor, setRestoreFor] = React.useState(null);
        const [deleteFor, setDeleteFor] = React.useState(null);
        const [confirmText, setConfirmText] = React.useState('');
        const [error, setError] = React.useState('');

        /* The restore form's own state. Declared with the rest, because a hook
         * that is easy to move below a conditional return is a hook that will
         * eventually be moved below one. */
        const [restoreScopes, setRestoreScopes] = React.useState(null);
        /* Null until touched: "every channel in the archive" for the scopes, and for the
         * channels, none -- an empty array, because message history is the one thing a
         * restore should never start ticked. */
        const [restoreChannels, setRestoreChannels] = React.useState([]);
        const [safetyFirst, setSafetyFirst] = React.useState(true);
        const [redeploy, setRedeploy] = React.useState(false);
        const [deployForMessages, setDeployForMessages] = React.useState(false);
        /*
         * Replace is the default here, and merge is the default over the API. Not an
         * inconsistency: a parameter nobody sent must not delete anything, while somebody
         * restoring a backup in the console usually means "put it back how it was". The
         * console always sends the mode explicitly, and cannot run a replacing restore
         * until `removals` holds a list somebody has been shown.
         */
        const [replace, setReplace] = React.useState(true);
        const [removals, setRemovals] = React.useState(null);

        const dirty = React.useRef(false);

        const load = React.useCallback(async (keepForm) => {
            try {
                const payload = await call('GET', '/status');
                setStatus(payload);
                setError('');
                if (!keepForm && !dirty.current) {
                    setForm(settingsOf(payload));
                }
                return payload;
            } catch (err) {
                setError(err.message || String(err));
                return null;
            }
        }, []);

        React.useEffect(() => { load(false); }, [load]);

        /*
         * While something is running, poll. The Java side runs the backup on the
         * request thread, so the call that started it is still open -- this is
         * what draws the phase while that call has not returned, and it is also
         * what picks a run started on another node or in another browser tab.
         */
        React.useEffect(() => {
            if (!status || !(status.running === true || String(status.running) === 'true')) {
                return undefined;
            }
            const timer = window.setInterval(() => load(true), BUSY_POLL_MS);
            return () => window.clearInterval(timer);
        }, [status, load]);

        const value = (key, fallback) => {
            const v = form[key];
            return v === undefined || v === '' ? (fallback === undefined ? '' : fallback) : v;
        };
        const isOn = (key, fallback) => {
            const v = form[key];
            if (v === undefined || v === '') return !!fallback;
            return String(v) === 'true';
        };
        const set = (key, v) => {
            dirty.current = true;
            setForm(prev => Object.assign({}, prev, { [key]: String(v) }));
        };

        const destType = value('destination', 'keep');
        const destKey = k => `dest.${destType}.${k}`;
        const destValues = () => {
            const out = {};
            (DEST_FIELDS[destType] || []).forEach(f => {
                out[f.key] = f.secret ? (secrets[destKey(f.key)] || '')
                    : value(destKey(f.key), '');
            });
            return out;
        };

        async function save(extra) {
            setBusy(true);
            setMessage(null);
            try {
                const body = Object.assign({}, form, secrets, extra || {});
                // A secret whose box was left empty must not be sent at all: an
                // empty value means "not supplied" on the server, but sending a
                // key with an empty value for every secret on every save would
                // be a lot of no-ops and one day a bug.
                Object.keys(body).forEach(k => {
                    if (body[k] === '' && (k in secrets)) delete body[k];
                });
                const payload = await call('POST', '/settings', body);
                dirty.current = false;
                setSecrets({});
                setMessage({
                    kind: payload.valid === true || String(payload.valid) === 'true'
                        ? 'ok' : 'warn',
                    text: payload.valid === true || String(payload.valid) === 'true'
                        ? 'Saved.'
                        : 'Saved, but the settings are not yet usable.',
                    problems: payload.problems || [],
                });
                await load(false);
                // The save may have changed the destination, in which case the list
                // underneath is now describing somewhere else entirely.
                await loadArchives(false);
            } catch (err) {
                setMessage({ kind: 'error', text: err.message || String(err) });
            } finally {
                setBusy(false);
            }
        }

        async function act(path, body, describe) {
            setBusy(true);
            setMessage(null);
            try {
                const payload = await call('POST', path, body);
                const ok = payload.ok === true || String(payload.ok) === 'true';
                setMessage({
                    kind: ok ? (payload.problems && payload.problems.length ? 'warn' : 'ok')
                        : 'error',
                    text: ok ? describe(payload) : (payload.error || 'It did not work.'),
                    problems: payload.problems || [],
                    notes: payload.notes || [],
                });
                await load(false);
                /*
                 * Every action reachable through here can change what the destination
                 * holds -- a backup adds one, retention removes some, a restore's safety
                 * copy adds one, a probe proves the listing works. Re-listing after each
                 * is one request against storage the operator has just deliberately
                 * touched, and it is the difference between "Backed up to X" and actually
                 * seeing X in the list underneath.
                 */
                await loadArchives(false);
                return payload;
            } catch (err) {
                setMessage({ kind: 'error', text: err.message || String(err) });
                return null;
            } finally {
                setBusy(false);
            }
        }

        /*
         * Lists the configured destination.
         *
         * Unlike /status this one reaches the network -- it opens the bucket, the share or
         * the SFTP session -- so it is kept off the three-second poll that runs while a
         * backup is in progress. It is, though, fetched on mount and after anything that
         * can change what the destination holds, because a list that only refreshes when
         * somebody presses a button is a list that is usually wrong: taking a backup and
         * not seeing it appear is the obvious case.
         *
         * `explicit` marks a refresh somebody asked for. Those take the page-wide busy
         * flag; the automatic ones do not, because disabling every button on the page for
         * the length of an S3 round trip on page load would be worse than the staleness
         * this is fixing.
         */
        const loadArchives = React.useCallback(async (explicit) => {
            if (explicit) setBusy(true);
            setListing(true);
            try {
                const payload = await call('GET', '/archives');
                setArchives(Object.assign({ fetchedAt: Date.now() }, payload));
            } catch (err) {
                /*
                 * Kept inside the panel rather than raised as a page-level error. An
                 * unreachable destination is a fact about the destination, and it should
                 * not make the settings forms above it look like they failed.
                 */
                setArchives({ ok: false, error: err.message || String(err),
                    fetchedAt: Date.now() });
            } finally {
                setListing(false);
                if (explicit) setBusy(false);
            }
        }, []);

        // On mount, so the page opens showing what is actually in the destination.
        React.useEffect(() => { loadArchives(false); }, [loadArchives]);

        // --------------------------------------------------------------
        // Panels
        // --------------------------------------------------------------

        function renderSummary() {
            const running = status.running === true || String(status.running) === 'true';
            const scheduled = status.enabled === true || String(status.enabled) === 'true';
            const valid = status.valid === true || String(status.valid) === 'true';

            const lines = [
                ['Schedule', scheduled ? status.schedule : 'off'],
                ['Next run', scheduled && status.nextRun
                    ? `${when(status.nextRun)} (${ago(status.nextRun)})` : '—'],
                ['Last run', status.lastRun
                    ? `${when(status.lastRun)} (${ago(status.lastRun)})` : 'never'],
                ['Last success', status.lastSuccess
                    ? `${when(status.lastSuccess)} (${ago(status.lastSuccess)})` : 'never'],
                ['Destination', status.destination || '—'],
            ];

            return Panel('Backup', [
                running ? Banner('info', [
                    h('strong', { key: 's' }, `${status.operation || 'Working'}: `),
                    h('span', { key: 'p' }, status.phase || ''),
                    status.phaseDetail
                        ? h('span', { key: 'd', className: 'hint' },
                            ` — ${status.phaseDetail}`) : null,
                    status.runningSince
                        ? h('div', { key: 'w', className: 'hint' },
                            `started ${ago(status.runningSince)}`) : null,
                ], 'running') : null,

                !running && status.clusterRunningOn ? Banner('info',
                    `Another node (${status.clusterRunningOn}) is running a backup.`,
                    'cluster') : null,

                status.lastError ? Banner('error', [
                    h('strong', { key: 'l' }, 'Last run: '),
                    h('span', { key: 'e' }, status.lastError),
                ], 'lasterror') : null,

                !valid && (status.problems || []).length ? Banner('warn', [
                    h('div', { key: 't' }, 'Not ready to run:'),
                    h('ul', { key: 'l', style: { margin: '4px 0 0 18px' } },
                        (status.problems || []).map((p, i) =>
                            h('li', { key: i }, p))),
                ], 'invalid') : null,

                h('div', { key: 'kv', className: 'kv' },
                    lines.map(([label, text], i) => h('div', {
                        key: i,
                        style: { display: 'flex', gap: '10px', marginBottom: '3px' },
                    }, [
                        h('span', { key: 'l', className: 'hint',
                            style: { minWidth: '11ch' } }, label),
                        h('span', { key: 'v' }, text),
                    ]))),

                h('div', { key: 'a', style: { marginTop: '10px' } }, [
                    Button('Back up now', () => act('/run', undefined,
                        p => `Backed up to ${p.archive} (${p.size}) — ${p.summary}`), {
                        key: 'run', primary: true, disabled: busy || running || !valid,
                        title: valid ? '' : 'Fix the settings first',
                    }),
                    Button('Test destination', () => act('/probe', undefined,
                        p => p.result), { key: 'probe', disabled: busy || running }),
                    // Reloads both halves: the status, and the destination listing.
                    // "Refresh" that left the archive list as it was is the thing this
                    // button most obviously ought to do and did not.
                    Button('Reload', () => { load(false); loadArchives(true); },
                        { key: 'reload', disabled: busy }),
                ]),
            ], null, 'summary');
        }

        function renderWhat() {
            const scopes = rows(status.scopes, SCOPE_FIELDS);
            const selected = new Set(value('config.scope',
                scopes.map(s => s.token).join(',')).split(',').filter(Boolean));
            const channels = rows(status.channels, CHANNEL_FIELDS);
            const chosenChannels = new Set(
                value('messages.channels', '').split(',').filter(Boolean));
            const chosenStatuses = new Set(
                value('messages.statuses', '').split(',').filter(Boolean));

            const toggleScope = (token, on) => {
                const next = new Set(selected);
                if (on) next.add(token); else next.delete(token);
                // Written in declaration order so the stored value is stable and two
                // identical selections do not produce two different strings.
                set('config.scope', scopes.map(s => s.token)
                    .filter(t => next.has(t)).join(','));
            };

            return Panel('What to back up', [
                h('label', { key: 'cfg', style: { display: 'block', marginBottom: '6px' } }, [
                    h('input', { key: 'i', type: 'checkbox',
                        checked: isOn('config.included', true),
                        onChange: e => set('config.included', e.target.checked),
                        style: { marginRight: '6px' } }),
                    h('strong', { key: 't' }, 'Configuration'),
                ]),
                Hint('Everything is included unless you say otherwise. A backup that '
                    + 'quietly left something out is found to have left it out on the day '
                    + 'it is needed.', 'cfghint'),

                isOn('config.included', true) ? h('div', {
                    key: 'scopes',
                    style: {
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))',
                        gap: '2px 14px',
                        margin: '8px 0 4px 18px',
                    },
                }, scopes.map(scope => h('label', {
                    key: scope.token,
                    style: { display: 'flex', alignItems: 'center', gap: '6px' },
                    title: scope.portable === 'true' ? ''
                        : 'Holds this instance’s own identity or environment-specific '
                          + 'behaviour — worth checking before restoring it elsewhere',
                }, [
                    h('input', { key: 'i', type: 'checkbox',
                        checked: selected.has(scope.token),
                        onChange: e => toggleScope(scope.token, e.target.checked) }),
                    h('span', { key: 'l' }, scope.label),
                    scope.portable === 'true' ? null
                        : h('span', { key: 'p', className: 'hint' }, 'instance'),
                ]))) : null,

                isOn('config.included', true) ? h('div', { key: 'presets',
                    style: { margin: '4px 0 0 18px' } }, [
                    Button('All', () => set('config.scope',
                        scopes.map(s => s.token).join(',')), { key: 'all' }),
                    Button('Portable only', () => set('config.scope',
                        scopes.filter(s => s.portable === 'true')
                            .map(s => s.token).join(',')), {
                        key: 'portable',
                        title: 'Leaves out the parts that carry this instance’s own '
                             + 'name, mail server and pruning schedule',
                    }),
                ]) : null,

                Rule('r1'),

                h('label', { key: 'msg', style: { display: 'block', marginBottom: '6px' } }, [
                    h('input', { key: 'i', type: 'checkbox',
                        checked: isOn('messages.included', false),
                        onChange: e => set('messages.included', e.target.checked),
                        style: { marginRight: '6px' } }),
                    h('strong', { key: 't' }, 'Message history'),
                ]),
                Hint('Off by default. Configuration is megabytes; message history is '
                    + 'however many gigabytes this engine has accumulated, and every byte '
                    + 'of it is clinical content that will be sent wherever the destination '
                    + 'is.', 'msghint'),

                isOn('messages.included', false) ? h('div', {
                    key: 'msel', style: { margin: '10px 0 0 18px' },
                }, [
                    Field('Date range', h('select', {
                        value: value('messages.days', '0'),
                        onChange: e => set('messages.days', e.target.value),
                    }, [[0, 'All history'], [1, 'The last day'], [7, 'The last 7 days'],
                        [30, 'The last 30 days'], [90, 'The last 90 days'],
                        [365, 'The last year']].map(([v, label]) =>
                        h('option', { key: v, value: String(v) }, label))),
                    null, 'days'),

                    Field('Statuses', h('div', { style: { display: 'flex',
                        flexWrap: 'wrap', gap: '4px 12px' } },
                        STATUSES.map(s => h('label', { key: s,
                            style: { display: 'flex', alignItems: 'center', gap: '5px' } }, [
                            h('input', { key: 'i', type: 'checkbox',
                                checked: chosenStatuses.has(s),
                                onChange: e => {
                                    const next = new Set(chosenStatuses);
                                    if (e.target.checked) next.add(s); else next.delete(s);
                                    set('messages.statuses',
                                        STATUSES.filter(x => next.has(x)).join(','));
                                } }),
                            h('span', { key: 'l' }, s.toLowerCase()),
                        ]))),
                        'None ticked means every status, which is usually what a backup '
                        + 'wants.', 'statuses'),

                    Field('Channels', h('div', {
                        style: {
                            maxHeight: '190px', overflowY: 'auto',
                            border: '1px solid var(--line)', borderRadius: '4px',
                            padding: '6px 8px',
                        },
                    }, channels.length === 0
                        ? h('div', { className: 'hint' }, 'This engine has no channels.')
                        : channels.map(c => h('label', {
                            key: c.id,
                            style: { display: 'flex', alignItems: 'center', gap: '6px' },
                        }, [
                            h('input', { key: 'i', type: 'checkbox',
                                checked: chosenChannels.has(c.id),
                                onChange: e => {
                                    const next = new Set(chosenChannels);
                                    if (e.target.checked) next.add(c.id);
                                    else next.delete(c.id);
                                    set('messages.channels',
                                        channels.map(x => x.id)
                                            .filter(id => next.has(id)).join(','));
                                } }),
                            h('span', { key: 'l' }, c.name || c.id),
                        ]))),
                        'None ticked means every channel.', 'channels'),

                    h('label', { key: 'att',
                        style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                        h('input', { key: 'i', type: 'checkbox',
                            checked: isOn('messages.attachments', true),
                            onChange: e => set('messages.attachments', e.target.checked) }),
                        h('span', { key: 'l' }, 'Include attachments'),
                    ]),
                ]) : null,

                h('div', { key: 'save', style: { marginTop: '12px' } },
                    [Button('Save', () => save(), { key: 's', primary: true,
                        disabled: busy })]),
            ], null, 'what');
        }

        function renderSchedule() {
            const kind = value('schedule.kind', 'off');
            return Panel('When', [
                h('label', { key: 'en',
                    style: { display: 'flex', alignItems: 'center', gap: '6px',
                        marginBottom: '10px' } }, [
                    h('input', { key: 'i', type: 'checkbox',
                        checked: isOn('enabled', false),
                        onChange: e => set('enabled', e.target.checked) }),
                    h('span', { key: 'l' }, 'Run on a schedule'),
                ]),
                Hint('Leave this off until a backup has actually been taken and the '
                    + 'destination tested. The first unattended run should not be the first '
                    + 'run that has ever been tried.', 'enhint'),

                Field('Repeat', h('select', {
                    value: kind,
                    onChange: e => set('schedule.kind', e.target.value),
                }, [['off', 'Never (on request only)'], ['interval', 'Every N minutes'],
                    ['daily', 'Daily'], ['weekly', 'Weekly'], ['monthly', 'Monthly'],
                    ['cron', 'Cron expression']].map(([v, label]) =>
                    h('option', { key: v, value: v }, label))), null, 'kind'),

                kind === 'interval' ? Field('Every', h('input', {
                    type: 'number', min: 15, step: 15, style: { width: '110px' },
                    value: value('schedule.intervalMinutes', '1440'),
                    onChange: e => set('schedule.intervalMinutes', e.target.value),
                }), 'Minutes, measured from the end of the last run. At least 15.',
                    'interval') : null,

                kind === 'weekly' ? Field('On', h('select', {
                    value: value('schedule.dayOfWeek', '1'),
                    onChange: e => set('schedule.dayOfWeek', e.target.value),
                }, DAYS.map(([v, label]) =>
                    h('option', { key: v, value: String(v) }, label))), null, 'dow') : null,

                kind === 'monthly' ? Field('On day', h('select', {
                    value: value('schedule.dayOfMonth', '1'),
                    onChange: e => set('schedule.dayOfMonth', e.target.value),
                }, Array.from({ length: 28 }, (_, i) => i + 1).map(d =>
                    h('option', { key: d, value: String(d) }, String(d)))),
                    'The 29th to the 31st are not offered: a monthly backup set for the '
                    + '31st would silently skip seven months a year.', 'dom') : null,

                ['daily', 'weekly', 'monthly'].includes(kind) ? Field('At', h('input', {
                    type: 'time', style: { width: '130px' },
                    value: value('schedule.timeOfDay', '02:00'),
                    onChange: e => set('schedule.timeOfDay', e.target.value),
                }), null, 'time') : null,

                kind === 'cron' ? Field('Expression', h('input', {
                    type: 'text', className: 'mono', style: { width: '260px' },
                    placeholder: '0 2 * * *',
                    value: value('schedule.cron', ''),
                    onChange: e => set('schedule.cron', e.target.value),
                }), 'Five fields: minute hour day-of-month month day-of-week. '
                    + 'Steps, ranges, lists and three-letter names are supported. The '
                    + 'six-field Quartz form with seconds is refused, because reading its '
                    + 'seconds as minutes would fire at the wrong time without saying so.',
                    'cron') : null,

                kind !== 'off' ? Field('Time zone', h('input', {
                    type: 'text', style: { width: '230px' },
                    placeholder: 'Australia/Melbourne',
                    value: value('schedule.zone', ''),
                    onChange: e => set('schedule.zone', e.target.value),
                }), '"Two in the morning" is a local statement, and a container’s '
                    + 'clock is almost always UTC.', 'zone') : null,

                kind !== 'off' && status.scheduleExpression ? h('div', {
                    key: 'expr', className: 'hint', style: { marginBottom: '10px' },
                }, ['Currently: ', h('span', { key: 'm', className: 'mono' },
                    status.scheduleExpression), ` — ${status.schedule}`]) : null,

                Field('Label', h('input', {
                    type: 'text', style: { width: '230px' }, placeholder: 'nightly',
                    value: value('label', ''),
                    onChange: e => set('label', e.target.value),
                }), 'Optional. Added to the archive file name, so two environments writing '
                    + 'to one bucket stay distinguishable.', 'label'),

                h('div', { key: 'save' },
                    [Button('Save', () => save(), { key: 's', primary: true,
                        disabled: busy })]),
            ], null, 'schedule');
        }

        function renderDestination() {
            const types = rows(status.destinationTypes, DEST_TYPE_FIELDS);
            const fields = DEST_FIELDS[destType] || [];
            const current = destValues();

            return Panel('Where it goes', [
                Field('Destination', h('select', {
                    value: destType,
                    onChange: e => set('destination', e.target.value),
                }, types.map(t => h('option', { key: t.token, value: t.token }, t.label))),
                    destType === 'keep'
                        ? 'Archives stay on this engine, under appdata/, and are downloaded '
                          + 'from the list below. Fine for trying this out; not a backup, '
                          + 'because it is on the machine being backed up.'
                        : null,
                    'type'),

                fields.filter(f => !f.showWhen || f.showWhen(current))
                    .map(f => renderDestField(f, current)),

                h('div', { key: 'save', style: { marginTop: '4px' } }, [
                    Button('Save', () => save(), { key: 's', primary: true, disabled: busy }),
                    Button('Test destination', () => act('/probe', undefined, p => p.result),
                        { key: 'p', disabled: busy }),
                ]),
            ], null, 'destination');
        }

        function renderDestField(f, current) {
            const key = destKey(f.key);
            const stored = value(key, '');
            const label = f.label + (f.required ? ' *' : '');

            if (f.type === 'bool') {
                return h('div', { key: f.key, style: { marginBottom: '10px' } }, [
                    h('label', { key: 'l',
                        style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                        h('input', { key: 'i', type: 'checkbox',
                            checked: isOn(key, defaultBool(f.key)),
                            onChange: e => set(key, e.target.checked) }),
                        h('span', { key: 't' }, f.label),
                    ]),
                    Hint(f.hint, 'h'),
                ]);
            }

            if (f.type === 'select') {
                return Field(label, h('select', {
                    value: stored || f.options[0][0],
                    onChange: e => set(key, e.target.value),
                }, f.options.map(([v, text]) =>
                    h('option', { key: v || '_', value: v }, text))), f.hint, f.key);
            }

            if (f.secret) {
                const isSet = stored === 'set';
                const control = f.type === 'textarea'
                    ? h('textarea', {
                        rows: 6, className: 'mono',
                        style: { width: '100%', maxWidth: '62ch' },
                        placeholder: isSet ? '— set — leave empty to keep it'
                            : (f.placeholder || ''),
                        value: secrets[key] || '',
                        onChange: e => setSecrets(prev =>
                            Object.assign({}, prev, { [key]: e.target.value })),
                    })
                    : h('input', {
                        type: 'password', autoComplete: 'new-password',
                        style: { width: '100%', maxWidth: '42ch' },
                        placeholder: isSet ? '— set — leave empty to keep it'
                            : (f.placeholder || ''),
                        value: secrets[key] || '',
                        onChange: e => setSecrets(prev =>
                            Object.assign({}, prev, { [key]: e.target.value })),
                    });
                return h('div', { key: f.key, style: { marginBottom: '10px' } }, [
                    h('label', { key: 'l',
                        style: { display: 'block', marginBottom: '3px' } }, label),
                    control,
                    isSet ? h('div', { key: 'c', style: { marginTop: '4px' } },
                        [Button('Clear', () => save({ [key]: CLEAR_SECRET }),
                            { key: 'x', disabled: busy })]) : null,
                    Hint(f.hint || (isSet
                        ? 'Stored encrypted with the engine’s own key and never sent '
                          + 'back to this page.' : null), 'h'),
                ]);
            }

            const control = f.type === 'textarea'
                ? h('textarea', {
                    rows: 5, className: 'mono',
                    style: { width: '100%', maxWidth: '72ch' },
                    placeholder: f.placeholder || '',
                    value: stored,
                    onChange: e => set(key, e.target.value),
                })
                : h('input', {
                    type: f.type === 'number' ? 'number' : 'text',
                    style: { width: '100%', maxWidth: f.type === 'number' ? '12ch' : '52ch' },
                    placeholder: f.placeholder || '',
                    value: stored,
                    onChange: e => set(key, e.target.value),
                });
            return Field(label, control, f.hint, f.key);
        }

        /* The two boolean destination settings whose sensible default is true. */
        function defaultBool(key) {
            if (key === 'passive') return true;
            if (key === 'pathStyle') return destType === 's3'
                && !!value(destKey('endpoint'), '');
            return false;
        }

        function renderProtection() {
            const hasPassphrase = status.hasPassphrase === true
                || String(status.hasPassphrase) === 'true';
            const encrypting = isOn('encrypt', false);
            return Panel('Encryption and retention', [
                h('label', { key: 'e',
                    style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                    h('input', { key: 'i', type: 'checkbox', checked: encrypting,
                        onChange: e => set('encrypt', e.target.checked) }),
                    h('span', { key: 'l' }, 'Encrypt the archive (AES-256)'),
                ]),
                Hint('An archive holds every credential in the configuration map, the SMTP '
                    + 'password from the server settings and, with messages included, the '
                    + 'clinical content of everything this engine has processed — and '
                    + 'it is then handed to storage this engine does not control.',
                    'enchint'),

                encrypting ? h('div', { key: 'pp', style: { margin: '10px 0 0 18px' } }, [
                    Field('Passphrase', h('input', {
                        type: 'password', autoComplete: 'new-password',
                        style: { width: '100%', maxWidth: '42ch' },
                        placeholder: hasPassphrase
                            ? '— set — leave empty to keep it' : '',
                        value: secrets.passphrase || '',
                        onChange: e => setSecrets(prev =>
                            Object.assign({}, prev, { passphrase: e.target.value })),
                    }), null, 'p1'),
                    Banner('warn', 'There is no key escrow and no recovery. Keep this '
                        + 'passphrase somewhere that survives this engine — an engine '
                        + 'rebuilt from nothing cannot read its own archives without it, '
                        + 'which is exactly the situation they exist for.', 'ppwarn'),
                ]) : null,

                Rule('r'),

                h('div', { key: 'rt' }, [
                    h('strong', { key: 't' }, 'Retention'),
                    Hint('Two limits. An archive goes when either selects it — except '
                        + 'that nothing is removed for age if doing so would take the count '
                        + 'below the keep-count, so a quiet month cannot empty the '
                        + 'destination.', 'rthint'),
                ]),
                h('div', { key: 'lims', style: { display: 'flex', gap: '18px',
                    margin: '8px 0 0 0', flexWrap: 'wrap' } }, [
                    Field('Keep at least', h('input', {
                        type: 'number', min: 0, style: { width: '90px' },
                        value: value('retention.keepCount', '0'),
                        onChange: e => set('retention.keepCount', e.target.value),
                    }), '0 means no count limit.', 'kc'),
                    Field('Keep for (days)', h('input', {
                        type: 'number', min: 0, style: { width: '90px' },
                        value: value('retention.keepDays', '0'),
                        onChange: e => set('retention.keepDays', e.target.value),
                    }), '0 means no age limit.', 'kd'),
                ]),
                h('label', { key: 'pr',
                    style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                    h('input', { key: 'i', type: 'checkbox',
                        checked: isOn('retention.prune', false),
                        onChange: e => set('retention.prune', e.target.checked) }),
                    h('span', { key: 'l' }, 'Let retention delete old archives'),
                ]),
                Hint('Off by default, and separate from the limits on purpose: setting '
                    + '"keep 7" should not by itself be an instruction to delete. The '
                    + 'archive list below shows exactly which files the current limits '
                    + 'would remove.', 'prhint'),

                h('div', { key: 'save', style: { marginTop: '12px' } },
                    [Button('Save', () => save(), { key: 's', primary: true,
                        disabled: busy })]),
            ], null, 'protection');
        }

        function renderArchives() {
            const ok = archives
                && (archives.ok === true || String(archives.ok) === 'true');
            const list = ok ? rows(archives.archives, ARCHIVE_FIELDS) : null;
            const doomed = new Set(archives ? (archives.prunePreview || []) : []);
            const local = archives
                && (archives.local === true || String(archives.local) === 'true');
            const uploads = rows(status.uploads, ARCHIVE_FIELDS);

            /* What this list is of, in the header, so it can never be mistaken for
             * something else -- the panel is a long way down the page from the
             * destination form, and the whole point of it is that it shows the target. */
            const heading = h('span', { className: 'hint' }, listing
                ? 'listing…'
                : (archives && archives.destination) || status.destination || '');

            return Panel('Archives in the destination', [
                h('div', { key: 'a', style: { marginBottom: '10px' } }, [
                    Button(listing ? 'Refreshing…' : 'Refresh',
                        () => loadArchives(true),
                        { key: 'l', disabled: busy || listing }),
                    h('label', {
                        key: 'u', className: 'btn',
                        style: { display: 'inline-block', cursor: 'pointer' },
                    }, ['Upload an archive…', h('input', {
                        key: 'i', type: 'file', accept: '.zip,.enc',
                        style: { display: 'none' },
                        onChange: e => upload(e.target.files && e.target.files[0]),
                    })]),
                ]),

                archives && !ok ? Banner('error', [
                    h('div', { key: 't' }, ['Could not list the destination: ',
                        archives.error || 'unknown error']),
                    h('div', { key: 'h', className: 'hint', style: { marginTop: '4px' } },
                        'The settings above may still be wrong, or the storage may be '
                        + 'unreachable from this engine. Test destination reports which.'),
                ], 'lerr') : null,

                /*
                 * "Keep on this engine" is the one destination that is not shared, so in a
                 * cluster this list is whichever node answered the request. Said here
                 * rather than left to be discovered by a download that 404s.
                 */
                ok && local ? h('div', { key: 'lh', className: 'hint',
                    style: { marginBottom: '8px' } },
                    'Kept on this engine: this is one node’s own directory, and in a '
                    + 'cluster another node will show a different list.') : null,

                list === null
                    ? h('div', { key: 'n', className: 'hint' },
                        archives ? '' : 'Listing the destination…')
                    : list.length === 0
                        ? h('div', { key: 'e', className: 'hint' },
                            'No archives in the destination yet.')
                        : h('div', { key: 't' },
                            list.map(a => renderArchiveRow(a, doomed.has(a.name), local))),

                ok && list && list.length > 0 && archives.fetchedAt
                    ? h('div', { key: 'when', className: 'hint',
                        style: { marginTop: '8px' } },
                        `${plural(list.length, 'archive')}, listed ${ago(
                            new Date(archives.fetchedAt).toISOString())}`) : null,

                /*
                 * Kept visually separate from the list above, and labelled, because these
                 * two are easy to confuse and mean very different things: the list above
                 * is the destination, this is a staging area on this engine's own disk.
                 */
                uploads.length > 0 ? h('div', { key: 'up', style: { marginTop: '14px' } }, [
                    h('strong', { key: 't' }, 'Uploaded to this engine'),
                    Hint('Not in the destination — these were uploaded here to be '
                        + 'restored from. An uploaded archive is a complete copy of another '
                        + 'engine’s credentials and messages sitting on this '
                        + 'one’s disk, so remove it once it has been restored.', 'h'),
                    h('div', { key: 'l', style: { marginTop: '6px' } },
                        uploads.map(a => renderArchiveRow(a, false, false, true))),
                ]) : null,
            ], heading, 'archives');
        }

        function renderArchiveRow(a, willPrune, local, uploaded) {
            const encrypted = a.encrypted === 'true';
            return h('div', {
                key: (uploaded ? 'u:' : 'a:') + a.name,
                style: {
                    display: 'flex', alignItems: 'center', gap: '10px',
                    padding: '6px 0', borderTop: '1px solid var(--line)', flexWrap: 'wrap',
                },
            }, [
                h('span', { key: 'n', className: 'mono',
                    style: { flex: '1 1 30ch', minWidth: '24ch' } }, a.name),
                h('span', { key: 's', className: 'hint',
                    style: { minWidth: '8ch' } }, a.size),
                h('span', { key: 'm', className: 'hint',
                    style: { minWidth: '16ch' } },
                    a.modified ? when(a.modified) : ''),
                encrypted ? h('span', { key: 'e', className: 'tag' }, 'encrypted') : null,
                willPrune ? h('span', { key: 'p', className: 'tag',
                    title: 'The current retention limits select this one for removal' },
                    'retention') : null,
                h('span', { key: 'b', style: { marginLeft: 'auto' } }, [
                    // Only archives on this engine: one on S3 is downloaded from S3
                    // rather than proxied through the engine's request threads.
                    local && !uploaded
                        ? Button('Download', () => download(a.name),
                            { key: 'd', disabled: busy }) : null,
                    Button('Inspect', () => inspect(a.name, uploaded),
                        { key: 'i', disabled: busy }),
                    Button('Restore…', () => {
                        setRestoreFor({ name: a.name, encrypted });
                        setInspected(null);
                        setConfirmText('');
                        // Reset the selection, so opening a second archive does not
                        // inherit the boxes ticked for the first one.
                        setRestoreScopes(null);
                        setRestoreChannels([]);
                        setRemovals(null);
                        inspect(a.name, uploaded);
                    }, { key: 'r', disabled: busy }),
                    Button('Delete', () => { setDeleteFor(
                        { name: a.name, uploaded: !!uploaded }); setConfirmText(''); },
                        { key: 'x', danger: true, disabled: busy }),
                ]),
            ]);
        }

        async function download(name) {
            /*
             * Fetched rather than linked, because the engine's API wants the
             * X-Requested-With header and an anchor cannot set one. The blob is
             * held in memory for the moment it takes to hand it to the browser,
             * which is why downloading is only offered for archives kept on this
             * engine -- one on S3 is fetched from S3.
             */
            setBusy(true);
            try {
                const res = await fetch(`${API}/download?name=${encodeURIComponent(name)}`, {
                    headers: { 'X-Requested-With': 'oie-webadmin' },
                    credentials: 'same-origin',
                });
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const blob = await res.blob();
                const url = URL.createObjectURL(blob);
                const anchor = document.createElement('a');
                anchor.href = url;
                anchor.download = name;
                document.body.appendChild(anchor);
                anchor.click();
                document.body.removeChild(anchor);
                URL.revokeObjectURL(url);
            } catch (err) {
                setMessage({ kind: 'error',
                    text: `Could not download ${name}: ${err.message || err}` });
            } finally {
                setBusy(false);
            }
        }

        async function upload(file) {
            if (!file) return;
            setBusy(true);
            setMessage(null);
            try {
                const res = await fetch(
                    `${API}/upload?name=${encodeURIComponent(file.name)}`, {
                        method: 'POST',
                        headers: {
                            'X-Requested-With': 'oie-webadmin',
                            'Content-Type': 'application/octet-stream',
                        },
                        credentials: 'same-origin',
                        body: file,
                    });
                const text = await res.text();
                const payload = text ? decode(JSON.parse(text)) : {};
                const ok = payload.ok === true || String(payload.ok) === 'true';
                setMessage({
                    kind: ok ? 'ok' : 'error',
                    text: ok ? `Uploaded ${payload.name} (${payload.size}).`
                        : (payload.error || `HTTP ${res.status}`),
                });
                await load(false);
            } catch (err) {
                setMessage({ kind: 'error', text: err.message || String(err) });
            } finally {
                setBusy(false);
            }
        }

        /*
         * Asks what a replacing restore would remove, for the scopes currently ticked.
         *
         * Deliberately not automatic. It fetches and unpacks the whole archive, so running
         * it on every checkbox click would mean a download per tick; and a list that
         * appears on its own is a list people scroll past, whereas one they asked for is
         * one they read.
         */
        async function previewRemovals() {
            if (!restoreFor) return;
            setBusy(true);
            try {
                // Recomputed from state rather than read out of the render, so this stays
                // a pure function of what is ticked right now.
                const inArchive = inspected && inspected.ok !== false
                    ? String(inspected.scopes || '').split(',').filter(Boolean) : [];
                const sel = restoreScopes === null ? inArchive : restoreScopes;
                const payload = await call('POST', '/restore/preview', {
                    name: restoreFor.name,
                    scope: sel.length === 0 ? 'none' : sel.join(','),
                    passphrase: secrets.restorePassphrase || '',
                });
                const ok = payload.ok === true || String(payload.ok) === 'true';
                if (!ok) {
                    setMessage({ kind: 'error',
                        text: payload.error || 'Could not work out what would be removed.' });
                    return;
                }
                setRemovals(rows(payload.removals, REMOVAL_FIELDS));
            } catch (err) {
                setMessage({ kind: 'error', text: err.message || String(err) });
            } finally {
                setBusy(false);
            }
        }

        async function inspect(name, uploaded) {
            setBusy(true);
            try {
                const payload = await call('POST', '/inspect', {
                    name,
                    passphrase: secrets.restorePassphrase || '',
                });
                const ok = payload.ok === true || String(payload.ok) === 'true';
                setInspected(Object.assign({ name, uploaded }, payload));
                if (!ok) {
                    setMessage({ kind: 'error', text: payload.error || 'Could not read it.' });
                }
            } catch (err) {
                setMessage({ kind: 'error', text: err.message || String(err) });
            } finally {
                setBusy(false);
            }
        }

        function renderInspected() {
            if (!inspected) return null;
            const ok = inspected.ok === true || String(inspected.ok) === 'true';
            const counts = rows(inspected.counts, COUNT_FIELDS);
            const channels = rows(inspected.messageChannels, MESSAGE_CHANNEL_FIELDS);

            return Panel(`Inside ${inspected.name}`, [
                !ok ? Banner('error', inspected.error
                    || 'This archive could not be read.', 'e') : null,
                ok ? h('div', { key: 's' }, [
                    h('div', { key: 'when', className: 'hint' },
                        `Taken ${when(inspected.createdAt)} — ${inspected.summary}`),
                    counts.length ? h('div', { key: 'c', style: { marginTop: '8px' } }, [
                        h('strong', { key: 't' }, 'Configuration'),
                        h('div', { key: 'l', style: {
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))',
                            gap: '1px 14px', marginTop: '4px',
                        } }, counts.map(c => h('div', { key: c.token },
                            [c.label, ' ', h('span', { key: 'n', className: 'hint' },
                                c.count)]))),
                    ]) : null,
                    channels.length ? h('div', { key: 'm', style: { marginTop: '10px' } }, [
                        h('strong', { key: 't' }, 'Messages'),
                        h('div', { key: 'l', style: { marginTop: '4px' } },
                            channels.map((c, i) => h('div', { key: i },
                                [c.name || c.id, ' ',
                                 h('span', { key: 'n', className: 'hint' },
                                     `${c.count} message(s)`)]))),
                    ]) : null,
                ]) : null,
                h('div', { key: 'close', style: { marginTop: '10px' } },
                    [Button('Close', () => setInspected(null), { key: 'c' })]),
            ], null, 'inspected', { reveal: true });
        }

        function renderRestore() {
            if (!restoreFor) return null;
            const armed = confirmText.trim() === restoreFor.name;
            const inspectedOk = inspected && inspected.name === restoreFor.name
                && (inspected.ok === true || String(inspected.ok) === 'true');
            const archiveScopes = inspectedOk ? String(inspected.scopes || '')
                .split(',').filter(Boolean) : [];
            const allScopes = rows(status.scopes, SCOPE_FIELDS);
            /*
             * Null means "not touched yet", which renders as everything ticked. It is kept
             * distinct from the empty array so that unticking the last box is a real state
             * and not indistinguishable from never having looked -- the server reads an
             * empty list as "restore no configuration", and the page has to be able to
             * express that.
             */
            const chosen = restoreScopes;
            /* Changing the selection invalidates any removal preview: it was computed
             * against a different set of ticked boxes, and showing a stale list next to a
             * button that deletes is worse than showing none. */
            const setChosen = (next) => { setRestoreScopes(next); setRemovals(null); };
            const inArchive = allScopes.filter(s => archiveScopes.includes(s.token));
            const scopeCounts = {};
            rows(inspected && inspected.counts, COUNT_FIELDS)
                .forEach(c => { scopeCounts[c.token] = c.count; });

            const archiveChannels = inspectedOk
                ? rows(inspected.messageChannels, MESSAGE_CHANNEL_FIELDS) : [];
            const chosenChannels = restoreChannels;
            const channelsPicked = chosenChannels === null
                ? archiveChannels.map(c => c.id) : chosenChannels;
            const messagesOn = archiveChannels.length > 0 && channelsPicked.length > 0;

            const selectedScopes = chosen === null ? inArchive.map(s => s.token) : chosen;
            const nothingSelected = selectedScopes.length === 0 && !messagesOn;
            /* A replacing restore deletes, so the list of what it would delete has to have
             * been asked for and shown. Changing the selection clears it, because a list
             * computed against a different set of ticked boxes is the wrong list. */
            const needsPreview = replace && selectedScopes.length > 0 && removals === null;

            return Panel(`Restore ${restoreFor.name}`, [
                Banner('warn', [
                    h('div', { key: '1' }, 'This writes the archive’s configuration '
                        + 'over what this engine holds now. It does not delete anything the '
                        + 'archive does not contain, so a restore adds back what was lost '
                        + 'rather than rolling the engine back to a moment in time.'),
                    h('div', { key: '2', style: { marginTop: '6px' } },
                        'A deployed channel keeps running the version it was deployed with '
                        + 'until it is redeployed.'),
                ], 'w'),

                restoreFor.encrypted ? Field('Passphrase', h('input', {
                    type: 'password', autoComplete: 'new-password',
                    style: { width: '100%', maxWidth: '42ch' },
                    placeholder: 'leave empty to use the stored one',
                    value: secrets.restorePassphrase || '',
                    onChange: e => setSecrets(prev =>
                        Object.assign({}, prev, { restorePassphrase: e.target.value })),
                }), null, 'pp') : null,

                inspectedOk ? h('div', { key: 'scopes' }, [
                    h('strong', { key: 't' }, 'Configuration to restore'),
                    h('div', { key: 'p', style: { margin: '6px 0' } }, [
                        Button('All', () => setChosen(inArchive.map(s => s.token)),
                            { key: 'a' }),
                        Button('None', () => setChosen([]), { key: 'n' }),
                        inArchive.some(s => s.token === 'channels')
                            ? Button('Channels only', () => setChosen(['channels']),
                                { key: 'c' }) : null,
                    ]),
                    inArchive.length === 0
                        ? h('div', { key: 'e', className: 'hint' },
                            'This archive holds no configuration.')
                        : h('div', { key: 'l', style: {
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                            gap: '2px 14px', margin: '0 0 4px 0',
                        } }, inArchive.map(s => h('label', {
                            key: s.token,
                            style: { display: 'flex', alignItems: 'center', gap: '6px' },
                        }, [
                            h('input', { key: 'i', type: 'checkbox',
                                checked: selectedScopes.includes(s.token),
                                onChange: e => {
                                    const next = e.target.checked
                                        ? selectedScopes.concat([s.token])
                                        : selectedScopes.filter(t => t !== s.token);
                                    setChosen(next);
                                } }),
                            h('span', { key: 'l' }, s.label),
                            scopeCounts[s.token] !== undefined
                                ? h('span', { key: 'n', className: 'hint' },
                                    scopeCounts[s.token]) : null,
                            s.portable === 'true' ? null
                                : h('span', { key: 'p', className: 'hint',
                                    title: 'Holds this instance’s own identity or '
                                         + 'environment-specific behaviour' }, 'instance'),
                        ]))),
                ]) : h('div', { key: 'wait', className: 'hint' },
                    'Reading the archive…'),

                inspectedOk ? h('div', { key: 'msg', style: { marginTop: '12px' } }, [
                    h('strong', { key: 't' }, 'Messages to restore'),
                    archiveChannels.length === 0
                        ? h('div', { key: 'e', className: 'hint',
                            style: { marginTop: '4px' } },
                            'This archive holds no messages.')
                        : h('div', { key: 'b' }, [
                            h('div', { key: 'p', style: { margin: '6px 0' } }, [
                                Button('All', () => setRestoreChannels(
                                    archiveChannels.map(c => c.id)), { key: 'a' }),
                                Button('None', () => setRestoreChannels([]), { key: 'n' }),
                            ]),
                            h('div', {
                                key: 'l',
                                style: {
                                    maxHeight: '190px', overflowY: 'auto',
                                    border: '1px solid var(--line)', borderRadius: '4px',
                                    padding: '6px 8px',
                                },
                            }, archiveChannels.map(c => h('label', {
                                key: c.id,
                                style: { display: 'flex', alignItems: 'center', gap: '6px' },
                            }, [
                                h('input', { key: 'i', type: 'checkbox',
                                    checked: channelsPicked.includes(c.id),
                                    onChange: e => {
                                        const next = e.target.checked
                                            ? channelsPicked.concat([c.id])
                                            : channelsPicked.filter(id => id !== c.id);
                                        setRestoreChannels(next);
                                    } }),
                                h('span', { key: 'l' }, c.name || c.id),
                                h('span', { key: 'n', className: 'hint' },
                                    `${c.count} message(s)`),
                            ]))),
                            Hint('Messages are added, not replaced: each one gets a new '
                                + 'message id on this engine and keeps its original as an '
                                + 'import id, so restoring the same archive twice makes '
                                + 'two copies.', 'mh'),
                        ]),
                ]) : null,

                /*
                 * Messages default to none even when the archive has them. An archive that
                 * happens to carry a year of message history should not start ticked while
                 * somebody is restoring a channel definition -- the expensive direction
                 * here is doing more than was asked, and undoing an import of four million
                 * messages is not a thing that can be done.
                 */
                inspectedOk && archiveChannels.length > 0 && !messagesOn
                    ? h('div', { key: 'mn', className: 'hint' },
                        `No messages selected — ${plural(archiveChannels.length, 'channel')} `
                        + 'of history in this archive will not be touched.') : null,

                messagesOn ? h('div', { key: 'dep', style: { marginTop: '10px' } }, [
                    h('label', {
                        key: 'l',
                        style: { display: 'flex', alignItems: 'center', gap: '6px' },
                    }, [
                        h('input', { key: 'i', type: 'checkbox', checked: deployForMessages,
                            onChange: e => setDeployForMessages(e.target.checked) }),
                        h('span', { key: 't' },
                            'Deploy these channels first if they are not deployed'),
                    ]),
                    Hint('The engine creates a channel’s message tables when it is '
                        + 'deployed and will not import into one that is not, so without '
                        + 'this an undeployed channel is reported and skipped. Deploying a '
                        + 'channel also starts it: it begins listening, polling and '
                        + 'sending. Nothing is undeployed afterwards.', 'h'),
                ]) : null,

                selectedScopes.length > 0 ? h('div', { key: 'mode',
                    style: { marginTop: '12px' } }, [
                    h('strong', { key: 't' }, 'What to do with anything the archive does '
                        + 'not contain'),
                    h('div', { key: 'r', style: { marginTop: '6px' } }, [
                        h('label', { key: 'rep',
                            style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                            h('input', { key: 'i', type: 'radio', name: 'oie-restore-mode',
                                checked: replace,
                                onChange: () => { setReplace(true); setRemovals(null); } }),
                            h('span', { key: 'l' },
                                'Replace — make this engine match the archive'),
                        ]),
                        h('label', { key: 'mrg',
                            style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                            h('input', { key: 'i', type: 'radio', name: 'oie-restore-mode',
                                checked: !replace,
                                onChange: () => { setReplace(false); setRemovals(null); } }),
                            h('span', { key: 'l' },
                                'Merge — add and update, remove nothing'),
                        ]),
                    ]),
                    Hint(replace
                        ? 'Anything in the selected parts that this archive does not '
                          + 'describe is removed, so the engine ends up holding exactly '
                          + 'what the backup held. Removing a channel takes its message '
                          + 'history with it and there is no undo, so the list has to be '
                          + 'shown before this can run.'
                        : 'Objects this archive does not contain are left exactly as they '
                          + 'are, so the restore adds back what was lost rather than '
                          + 'rolling the engine back to a moment in time.', 'mh'),

                    replace ? h('div', { key: 'prev', style: { marginTop: '8px' } }, [
                        Button(removals === null ? 'Show what would be removed'
                            : 'Check again', previewRemovals,
                            { key: 'p', disabled: busy }),
                        removals === null
                            ? h('div', { key: 'n', className: 'hint',
                                style: { marginTop: '4px' } },
                                'Required before a replacing restore can run.')
                            : removals.length === 0
                                ? Banner('ok', 'Nothing would be removed: this engine '
                                    + 'already holds only what the archive describes.', 'e')
                                : h('div', { key: 'l', style: { marginTop: '6px' } }, [
                                    Banner(removals.some(r => r.destroys === 'true')
                                        ? 'error' : 'warn',
                                        `${plural(removals.length, 'object')} would be `
                                        + 'removed from this engine'
                                        + (removals.some(r => r.destroys === 'true')
                                            ? ', and the message history of every channel '
                                              + 'among them goes with it. There is no undo.'
                                            : '.'), 'b'),
                                    h('div', {
                                        key: 'rows',
                                        style: {
                                            maxHeight: '200px', overflowY: 'auto',
                                            border: '1px solid var(--line)',
                                            borderRadius: '4px', padding: '6px 8px',
                                        },
                                    }, removals.map((r, i) => h('div', {
                                        key: i,
                                        style: { display: 'flex', gap: '10px',
                                            alignItems: 'baseline', flexWrap: 'wrap' },
                                    }, [
                                        h('span', { key: 's', className: 'hint',
                                            style: { minWidth: '18ch' } }, r.label),
                                        h('span', { key: 'n' }, r.name),
                                        r.deployed === 'deployed'
                                            ? h('span', { key: 'd', className: 'tag' },
                                                'deployed') : null,
                                        r.destroys === 'true'
                                            ? h('span', { key: 'h', className: 'tag',
                                                style: { borderColor: 'var(--err)',
                                                    color: 'var(--err)' },
                                                title: 'Its message history goes too' },
                                                'history') : null,
                                    ]))),
                                ]),
                    ]) : null,
                ]) : null,

                h('label', { key: 'safe',
                    style: { display: 'flex', alignItems: 'center', gap: '6px',
                        marginTop: '12px' } }, [
                    h('input', { key: 'i', type: 'checkbox', checked: safetyFirst,
                        onChange: e => setSafetyFirst(e.target.checked) }),
                    h('span', { key: 'l' },
                        'Take a configuration backup of this engine first'),
                ]),
                Hint('Kept on this engine. Configuration only — including the message '
                    + 'history would turn a safety net into an hours-long operation that '
                    + 'somebody mid-incident would be right to skip, and a skipped net is '
                    + 'no net.', 'sh'),

                h('label', { key: 'rd',
                    style: { display: 'flex', alignItems: 'center', gap: '6px',
                        marginTop: '8px' } }, [
                    h('input', { key: 'i', type: 'checkbox', checked: redeploy,
                        onChange: e => setRedeploy(e.target.checked) }),
                    h('span', { key: 'l' }, 'Redeploy every channel afterwards'),
                ]),
                Hint('Otherwise the restored definitions sit in the database while the '
                    + 'engine keeps running the deployed ones.', 'rh'),

                Rule('r'),

                /* What is about to happen, in one line, above the confirmation. The list of
                 * ticked boxes is above the fold and the button is below it, and a summary
                 * next to the thing being typed is what stops a restore that ticked the
                 * wrong box. */
                h('div', { key: 'sum', style: { marginBottom: '8px' } },
                    nothingSelected
                        ? h('span', { style: { color: 'var(--err)' } },
                            'Nothing is selected, so there is nothing to restore.')
                        : h('span', {}, ['Will restore ',
                            h('strong', { key: 'a' }, selectedScopes.length === 0
                                ? 'no configuration'
                                : plural(selectedScopes.length, 'configuration part')),
                            ' and ',
                            h('strong', { key: 'b' }, messagesOn
                                ? 'messages from '
                                    + plural(channelsPicked.length, 'channel')
                                : 'no messages'),
                            '.'])),

                Field('Type the archive name to confirm', h('input', {
                    type: 'text', className: 'mono',
                    style: { width: '100%', maxWidth: '42ch' },
                    value: confirmText,
                    onChange: e => setConfirmText(e.target.value),
                    placeholder: restoreFor.name,
                }), 'A second click is only a slower way to click.', 'confirm'),

                h('div', { key: 'go' }, [
                    Button('Restore', async () => {
                        const payload = await act('/restore', {
                            /*
                             * Both lists are always sent explicitly, and "none" rather than
                             * an empty string for an empty one. The server reads an omitted
                             * key as "everything of that kind", which is right for a
                             * scripted restore and exactly wrong for a form where somebody
                             * has just unticked every box.
                             */
                            name: restoreFor.name,
                            scope: selectedScopes.length === 0
                                ? 'none' : selectedScopes.join(','),
                            channels: messagesOn ? channelsPicked.join(',') : 'none',
                            passphrase: secrets.restorePassphrase || '',
                            safetyBackup: String(safetyFirst),
                            redeploy: String(redeploy),
                            deployForMessages: String(deployForMessages),
                            replace: String(replace && selectedScopes.length > 0),
                        }, p => `Restored ${p.archive} (${p.restoredScopes
                            || 'no configuration'}${p.messagesRestored
                            ? `, ${p.messagesRestored} message(s)` : ''}) in ${p.took}.`);
                        if (payload && (payload.ok === true
                            || String(payload.ok) === 'true')) {
                            setRestoreFor(null);
                            setConfirmText('');
                            setRestoreScopes(null);
                            setRestoreChannels([]);
                            setRemovals(null);
                        }
                    }, { key: 'r', danger: true,
                        disabled: busy || !armed || !inspectedOk || nothingSelected
                            || needsPreview,
                        title: nothingSelected
                            ? 'Tick at least one configuration part or one channel'
                            : needsPreview
                                ? 'Show what a replacing restore would remove first'
                                : '' }),
                    Button('Cancel', () => { setRestoreFor(null); setConfirmText('');
                        setRestoreScopes(null); setRestoreChannels([]);
                        setRemovals(null); },
                        { key: 'c', disabled: busy }),
                ]),
            ], null, 'restore', { reveal: true });
        }

        function renderDelete() {
            if (!deleteFor) return null;
            const armed = confirmText.trim() === deleteFor.name;
            return Panel(`Delete ${deleteFor.name}`, [
                Banner('error', 'This cannot be undone, and the archive is not moved '
                    + 'anywhere first.', 'w'),
                Field('Type the archive name to confirm', h('input', {
                    type: 'text', className: 'mono',
                    style: { width: '100%', maxWidth: '42ch' },
                    value: confirmText,
                    onChange: e => setConfirmText(e.target.value),
                    placeholder: deleteFor.name,
                }), null, 'confirm'),
                h('div', { key: 'go' }, [
                    Button('Delete', async () => {
                        const path = deleteFor.uploaded
                            ? `/uploads/_delete?name=${encodeURIComponent(deleteFor.name)}`
                            : `/archives/_delete?name=${encodeURIComponent(deleteFor.name)}`;
                        const payload = await act(path, undefined,
                            p => `Deleted ${p.deleted || deleteFor.name}.`);
                        if (payload && (payload.ok === true
                            || String(payload.ok) === 'true')) {
                            // act() has already re-listed the destination.
                            setDeleteFor(null);
                            setConfirmText('');
                        }
                    }, { key: 'd', danger: true, disabled: busy || !armed }),
                    Button('Cancel', () => { setDeleteFor(null); setConfirmText(''); },
                        { key: 'c', disabled: busy }),
                ]),
            ], null, 'delete', { reveal: true });
        }

        function renderHistory() {
            const list = rows(status.history, HISTORY_FIELDS);
            if (list.length === 0) {
                return Panel('History', h('div', { className: 'hint' },
                    'Nothing has run yet.'), null, 'history');
            }
            return Panel('History', h('div', {},
                list.map((row, i) => h('div', {
                    key: i,
                    style: {
                        display: 'flex', gap: '10px', padding: '6px 0',
                        borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                        flexWrap: 'wrap', alignItems: 'baseline',
                    },
                }, [
                    h('span', { key: 'o', className: 'tag',
                        style: row.outcome === 'failed'
                            ? { borderColor: 'var(--err)', color: 'var(--err)' }
                            : row.outcome === 'partial'
                                ? { borderColor: 'var(--warn)', color: 'var(--warn)' }
                                : {} }, row.outcome),
                    h('span', { key: 'w', style: { minWidth: '17ch' } },
                        when(row.startedAt)),
                    h('span', { key: 't', className: 'hint',
                        style: { minWidth: '8ch' } }, row.trigger),
                    h('span', { key: 'a', className: 'mono',
                        style: { flex: '1 1 24ch' } }, row.archive || '—'),
                    row.summary ? h('span', { key: 's', className: 'hint' },
                        row.summary) : null,
                    row.problems ? h('div', { key: 'p',
                        style: { flexBasis: '100%', color: 'var(--err)',
                            fontSize: '0.9em' } }, row.problems) : null,
                ]))), null, 'history');
        }

        // --------------------------------------------------------------

        if (!status) {
            return h('div', { className: 'view', style: viewStyle },
                error
                    ? Banner('error', error, 'e')
                    : h('div', { className: 'hint' }, 'Loading…'));
        }

        return h('div', { className: 'view', style: viewStyle }, [
            error ? Banner('error', error, 'err') : null,
            message ? Banner(message.kind, [
                h('div', { key: 't' }, message.text),
                (message.notes || []).length ? h('ul', { key: 'n',
                    style: { margin: '6px 0 0 18px' } },
                    message.notes.map((n, i) => h('li', { key: i }, n))) : null,
                (message.problems || []).length ? h('ul', { key: 'p',
                    style: { margin: '6px 0 0 18px', color: 'var(--err)' } },
                    message.problems.map((p, i) => h('li', { key: i }, p))) : null,
                h('div', { key: 'x', style: { marginTop: '6px' } },
                    [Button('Dismiss', () => setMessage(null), { key: 'd' })]),
            ], 'msg') : null,

            renderSummary(),
            renderRestore(),
            renderDelete(),
            renderInspected(),
            renderWhat(),
            renderSchedule(),
            renderDestination(),
            renderProtection(),
            renderArchives(),
            renderHistory(),
        ]);
    }

    // ------------------------------------------------------------------
    // Wiring
    //
    // Each registration is guarded on its own: a console that does not offer
    // one of these hooks should still get everything else, rather than the
    // whole plugin failing on the first missing function.
    // ------------------------------------------------------------------

    const ICON_BACKUP = 'M12 3v10m0 0 4-4m-4 4-4-4M4 15v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2';

    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[backup] could not register ' + label, err);
        }
    };

    safely('icon', () => {
        platform.registerIcon('backup', ICON_BACKUP);
    });

    safely('backup view', () => {
        platform.registerView('/backup', platform.reactView(BackupView), {
            title: 'Backup',
        });
        platform.registerNavItem({
            id: 'backup',
            label: 'Backup',
            icon: 'backup',
            path: '/backup',
            section: 'Plugins',
            order: 90,
        });
    });

    safely('command', () => {
        platform.registerCommand({
            id: 'backup',
            label: 'Backup',
            icon: 'backup',
            path: '/backup',
        });
    });
}
