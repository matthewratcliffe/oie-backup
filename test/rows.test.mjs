import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    decode, encodeMap, rows, escapeLines, unescapeLines, settingsOf,
    ARCHIVE_FIELDS, HISTORY_FIELDS,
} from './rows.mjs';

const TAB = '\t';

test('XStream map decoding', () => {
    // The shape the engine actually answers with for a Map<String, Object>.
    const payload = {
        'linked-hash-map': {
            entry: [
                { string: 'ok', boolean: true },
                { string: ['destination', 'S3: s3.ap-southeast-2.amazonaws.com/backups'] },
                { string: 'nextRun', 'string ': undefined },
            ],
        },
    };
    const decoded = decode(payload);
    assert.equal(decoded.ok, true);
    assert.equal(decoded.destination, 'S3: s3.ap-southeast-2.amazonaws.com/backups');
    console.log('ok   a map decodes to a plain object');

    // A list of strings -- every row-shaped field this plugin returns.
    const list = decode({ list: { string: ['a\tb', 'c\td'] } });
    assert.deepEqual(list, ['a\tb', 'c\td']);
    console.log('ok   a list of strings decodes to an array');

    // XStream drops the array when a list has exactly one element, which is the
    // case that breaks a naive decoder on an engine with one archive.
    assert.deepEqual(decode({ list: { string: 'only\trow' } }), ['only\trow']);
    console.log('ok   a single-element list is still an array');

    // And an empty one is an empty list, not a crash and not [undefined].
    assert.deepEqual(decode({ list: null }), []);
    console.log('ok   an empty list decodes to []');
});

test('map encoding for a settings POST', () => {
    const body = encodeMap({ 'schedule.cron': '0 2 * * *', label: 'a & b' });
    assert.match(body, /^<map>/);
    assert.match(body, /<string>schedule\.cron<\/string><string>0 2 \* \* \*<\/string>/);
    // An unescaped ampersand makes the engine's deserialiser answer 500, and the
    // failure looks like a rejected request rather than a mangled one.
    assert.match(body, /a &amp; b/);
    console.log('ok   values are XML-escaped');

    assert.equal(encodeMap({ a: undefined, b: null, c: '' }),
        '<map><entry><string>c</string><string></string></entry></map>');
    console.log('ok   undefined and null keys are dropped, empty string is kept');
});

test('archive rows', () => {
    const row = ['oie-backup-20260919T020000Z-nightly.zip.enc', '48123456', '46 MB',
        '2026-09-19T02:04:11Z', 'true'].join(TAB);
    const [a] = rows([row], ARCHIVE_FIELDS);
    assert.equal(a.name, 'oie-backup-20260919T020000Z-nightly.zip.enc');
    assert.equal(a.size, '46 MB');
    assert.equal(a.encrypted, 'true');
    console.log('ok   an archive row parses');

    // A destination that reports no modification time -- some FTP servers, and any
    // listing this plugin could not parse a date out of -- must still produce a row
    // rather than shifting every later field by one.
    const [b] = rows([['oie-backup-20260101T000000Z.zip', '10', '10 B', '', 'false']
        .join(TAB)], ARCHIVE_FIELDS);
    assert.equal(b.modified, '');
    assert.equal(b.encrypted, 'false');
    console.log('ok   a missing timestamp does not shift the fields');
});

test('history rows tolerate a shorter record', () => {
    // A row written by an older version of the plugin, read back after an upgrade --
    // which is exactly the moment somebody is looking at this page.
    const short = ['2026-09-19T02:00:00Z', '2026-09-19T02:04:11Z', 'scheduled', 'ok']
        .join(TAB);
    const [r] = rows([short], HISTORY_FIELDS);
    assert.equal(r.outcome, 'ok');
    assert.equal(r.archive, '');
    assert.equal(r.problems, '');
    console.log('ok   a truncated history row pads rather than throwing');
});

test('multi-line settings survive the round trip', () => {
    // The two settings that are genuinely multi-line. The row encoding strips
    // control characters, so without the escaping a pasted private key would come
    // back as one line -- and would then be saved back in that broken form.
    const knownHosts = 'backup.example.org ssh-ed25519 AAAAC3Nz\n'
        + '[backup.example.org]:2222 ssh-rsa AAAAB3Nz\n';
    const key = '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNza\\Q\n'
        + '-----END OPENSSH PRIVATE KEY-----\n';

    assert.equal(unescapeLines(escapeLines(knownHosts)), knownHosts);
    console.log('ok   known_hosts round trips');

    // A backslash inside the value is the case that breaks a naive escape: it must
    // not turn the following character into an escape sequence.
    assert.equal(unescapeLines(escapeLines(key)), key);
    console.log('ok   a value containing a backslash round trips');

    // Windows line endings normalise to one escape rather than two.
    assert.equal(escapeLines('a\r\nb'), 'a\\nb');
    console.log('ok   CRLF becomes a single escape');

    const settings = settingsOf({
        settings: [
            `dest.sftp.knownHosts${TAB}${escapeLines(knownHosts)}`,
            `dest.sftp.secret${TAB}set`,
            `schedule.cron${TAB}0 2 * * *`,
        ],
    });
    assert.equal(settings['dest.sftp.knownHosts'], knownHosts);
    assert.equal(settings['dest.sftp.secret'], 'set');
    assert.equal(settings['schedule.cron'], '0 2 * * *');
    console.log('ok   a settings payload decodes, secrets included as "set"');
});
