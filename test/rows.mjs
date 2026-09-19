/*
 * The pure helpers out of webadmin/web/plugin.js, so they can be tested without
 * a browser or a React runtime.
 *
 * Copied rather than imported, which is the same arrangement the Git Sync and
 * Volume Monitor extensions in this repository use: plugin.js is a module the
 * console loads and calls register() on, and importing it here would pull in
 * the whole page. The functions below are the ones with no DOM in them, and
 * they are short enough that keeping the two in step is a smaller cost than the
 * alternative of not testing them at all.
 */

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object.
 */
export function decode(node) {
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

export function encodeMap(obj) {
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

export function rows(list, names) {
    return (list || []).map(line => {
        const parts = String(line).split('\t');
        const out = {};
        names.forEach((name, i) => { out[name] = parts[i] === undefined ? '' : parts[i]; });
        return out;
    });
}

export function unescapeLines(value) {
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

/** The server side's BackupSettings.escapeLines, for round-trip testing. */
export function escapeLines(value) {
    return value === null || value === undefined ? ''
        : String(value).replace(/\\/g, '\\\\')
            .replace(/\r\n/g, '\\n')
            .replace(/\n/g, '\\n')
            .replace(/\r/g, '\\n');
}

export const SETTING_FIELDS = ['key', 'value'];
export const ARCHIVE_FIELDS = ['name', 'bytes', 'size', 'modified', 'encrypted'];
export const HISTORY_FIELDS = ['startedAt', 'finishedAt', 'trigger', 'outcome', 'archive',
    'bytes', 'destination', 'summary', 'problems'];

export function settingsOf(payload) {
    const out = {};
    rows(payload && payload.settings, SETTING_FIELDS)
        .forEach(row => { out[row.key] = unescapeLines(row.value || ''); });
    return out;
}
