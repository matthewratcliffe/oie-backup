#!/usr/bin/env bash
#
# Builds the Backup engine extension into dist/backup-<version>.zip.
#
#   ./plugins/oie-backup/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# Almost nothing is bundled. S3 and Azure go over the JDK's own HttpClient with
# hand-written request signing, SFTP over the engine's JSch (the same library
# the File connector uses), FTP over the engine's commons-net.
#
# The exception is jcifs-ng, for the SMB network-path destination. The engine
# ships it, but in client-lib and inside the File connector's own extension
# directory rather than on the shared server classpath, so an extension cannot
# rely on seeing it. It and slf4j-api are downloaded here and verified against
# the SHA-1 Maven Central publishes beside each one -- they get loaded into the
# engine's JVM, so an artifact changing under us would be arbitrary code
# execution inside the engine.
#
# Environment:
#   OIE_IMAGE      image to take engine jars from   (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                   (default eclipse-temurin:21-jdk)
#   PLUGIN_VERSION version stamped into plugin.xml   (default 0.1.0)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${HERE}"

OIE_IMAGE="${OIE_IMAGE:-oie/engine:4.6.0}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
PLUGIN_VERSION="${PLUGIN_VERSION:-0.1.0}"

# Must match the engine the extension is installed on, or ExtensionLoader
# refuses it outright -- compatibility is an exact string match.
MIRTH_VERSION="${MIRTH_VERSION:-4.6.0}"

# The same version the engine itself carries, so an operator who does end up with
# both on a classpath has one library rather than two.
JCIFS_VERSION="2.1.10"
SLF4J_VERSION="1.7.36"

declare -a MAVEN_DEPS=(
  "eu/agno3/jcifs/jcifs-ng/${JCIFS_VERSION}/jcifs-ng-${JCIFS_VERSION}.jar"
  "org/slf4j/slf4j-api/${SLF4J_VERSION}/slf4j-api-${SLF4J_VERSION}.jar"
)

BUILD="${HERE}/build"
LIBS="${BUILD}/libs"
DIST="${HERE}/dist"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

########################################################################
# 1. Engine jars, straight from the image
########################################################################
prepare_libs() {
    mkdir -p "$LIBS"

    if [[ -f "${LIBS}/.engine-jars-ok" ]]; then
        log "engine jars already extracted (rm -rf ${BUILD} to refresh)"
        return 0
    fi

    log "extracting engine jars from ${OIE_IMAGE}"
    # A throwaway container is the only way to read an image's filesystem
    # without a running one; `docker create` does not start it.
    local cid
    cid="$(docker create "$OIE_IMAGE" /bin/true)"
    trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' RETURN

    # Globbed rather than named with their versions. The engine bumps its
    # dependencies on its own schedule, and a build script naming
    # commons-net-3.9.0.jar fails with "no such file" on the first release that
    # ships 3.10 -- a version bump breaking the build of an extension whose own
    # code has not changed. The glob picks up whatever that engine actually has,
    # which is the jar this is meant to compile against anyway.
    #
    # One container resolves every pattern: the globs have to be expanded inside
    # the image, because they name files this host does not have.
    local -a patterns=(
        'server-lib/mirth-server.jar'
        'server-lib/mirth-client-core.jar'
        'server-lib/mirth-crypto.jar'
        'server-lib/xstream-*.jar'
        'server-lib/jsch-*.jar'
        'server-lib/donkey/donkey-server.jar'
        'server-lib/javax/javax.servlet-api-*.jar'
        'server-lib/commons/commons-net-*.jar'
        'client-lib/donkey-model.jar'
        'client-lib/log4j-api-*.jar'
        'client-lib/javax.ws.rs-api-*.jar'
        'client-lib/swagger-annotations-*.jar'
        'client-lib/jersey-media-multipart-*.jar'
    )
    # One line: the list is interpolated into a `for` in the container's shell,
    # and a newline after `in` is a syntax error there.
    local list="${patterns[*]}"

    local resolved
    resolved="$(docker run --rm --entrypoint sh "$OIE_IMAGE" -c "
        cd /opt/engine || exit 1
        for p in ${list}; do
            # First match only: a pattern is expected to name one jar, and two
            # copies of the same library on a classpath is its own problem.
            set -- \$p
            if [ -f \"\$1\" ]; then printf '%s\n' \"\$1\"; else printf 'MISSING %s\n' \"\$p\"; fi
        done")" || die "could not list jars in ${OIE_IMAGE}"

    if printf '%s\n' "$resolved" | grep -q '^MISSING '; then
        printf '%s\n' "$resolved" | grep '^MISSING ' >&2
        die "the engine image does not carry every jar this extension compiles against"
    fi

    local jar
    while IFS= read -r jar; do
        [[ -n "$jar" ]] || continue
        docker cp "${cid}:/opt/engine/${jar}" "${LIBS}/$(basename "$jar")" >/dev/null
    done <<< "$resolved"

    touch "${LIBS}/.engine-jars-ok"
    log "extracted $(ls -1 "${LIBS}"/*.jar | wc -l) engine jars"
}

########################################################################
# 2. The one bundled dependency
########################################################################
fetch_maven_deps() {
    mkdir -p "${LIBS}/bundled"
    local path url name expected actual
    for path in "${MAVEN_DEPS[@]}"; do
        name="$(basename "$path")"
        if [[ -f "${LIBS}/bundled/${name}" ]]; then continue; fi
        url="https://repo1.maven.org/maven2/${path}"
        log "downloading ${name}"
        curl --fail --silent --show-error --location --retry 3 \
            -o "${LIBS}/bundled/${name}" "$url"

        # Central publishes <artifact>.sha1 beside every artifact. Verifying
        # against it catches corruption and substitution without this script
        # carrying a hardcoded digest that nobody ever re-checks.
        expected="$(curl --fail --silent --show-error --location --retry 3 "${url}.sha1" | tr -d '[:space:]')"
        actual="$(sha1sum "${LIBS}/bundled/${name}" | cut -d' ' -f1)"
        if [[ -z "$expected" || "$expected" != "$actual" ]]; then
            rm -f "${LIBS}/bundled/${name}"
            printf 'checksum mismatch for %s: published %s, got %s\n' \
                "$name" "${expected:-<none>}" "$actual" >&2
            exit 1
        fi
        log "  sha1 verified"
    done

    log "bundled jars:"
    (cd "${LIBS}/bundled" && sha256sum ./*.jar | sed 's/^/    /')
}

########################################################################
# 3. Compile
########################################################################
compile() {
    log "compiling with ${JDK_IMAGE}"
    rm -rf "${BUILD}/classes"
    mkdir -p "${BUILD}/classes"

    # Paths are passed relative to the mount so this works the same on Windows.
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "${HERE}:/p" \
        -w /p \
        "$JDK_IMAGE" \
        sh -c '
            set -e
            CP=$(ls build/libs/*.jar build/libs/bundled/*.jar 2>/dev/null | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:all -Xlint:-options -Xlint:-serial -Werror \
                -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '
    log "compiled"
}

########################################################################
# 4. Self tests
########################################################################
#
# Three things in this extension fail silently rather than loudly, and each one
# is asserted here before the zip is built:
#
#   the schedule    a cron expression that is off by an hour, or that never
#                   fires, produces no error anywhere -- just backups that are
#                   not there on the day somebody looks for them
#   the encryption  an archive that encrypts but does not decrypt is discovered
#                   on the day it is needed, by definition
#   the S3 signing  a wrong canonical form fails with "the request signature we
#                   calculated does not match", which says nothing about which
#                   of ten inputs was wrong
#   the SMB paths   a path that converts wrongly connects to the wrong place, or
#                   refuses with a message about the path not being a directory,
#                   which sends people to check the share rather than the typing
selftest() {
    log "running the self tests"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        cat > /tmp/SelfTest.java <<EOF
public class SelfTest {
    public static void main(String[] args) throws Exception {
        check("cron", "org.openintegrationengine.plugins.backup.Cron");
        check("archive encryption", "org.openintegrationengine.plugins.backup.ArchiveCipher");
        check("AWS signing",
            "org.openintegrationengine.plugins.backup.destination.AwsV4Signer");
        check("TLS keystore merge", "org.openintegrationengine.plugins.backup.TlsStores");
        check("SMB paths",
            "org.openintegrationengine.plugins.backup.destination.SmbDestination");
        System.out.println(
            "    cron, encryption, request signing, SMB paths and TLS keystore merging all hold");
    }

    private static void check(String label, String className) throws Exception {
        Class<?> type = Class.forName(className);
        java.lang.reflect.Method method = type.getDeclaredMethod("selfTest");
        method.setAccessible(true);
        Object result = method.invoke(null);
        if (result != null) {
            System.err.println("    FAILED (" + label + "): " + result);
            System.exit(1);
        }
    }
}
EOF
        CP=$(ls build/libs/*.jar build/libs/bundled/*.jar 2>/dev/null | tr "\n" ":")
        javac -cp "build/classes:$CP" -d /tmp /tmp/SelfTest.java
        java -cp "build/classes:/tmp:$CP" SelfTest
    ' || die "a self test failed; refusing to package"
}

########################################################################
# 5. Package
########################################################################
package() {
    local stage="${BUILD}/stage/backup"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}/libs" "${DIST}"

    # One jar for the whole extension. The engine's extension classloader does
    # not care about the client/server/shared split unless the Swing client
    # needs its own half, and this plugin's UI is the web console.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c 'cd build/classes && jar cf ../stage/backup/libs/backup-server.jar .'

    cp "${LIBS}"/bundled/*.jar "${stage}/libs/"

    # plugin.xml carries the version and the compatibility string, both of which
    # have to be right or the extension is silently not loaded.
    sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
        -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
        -e "s|@JCIFS_VERSION@|${JCIFS_VERSION}|g" \
        -e "s|@SLF4J_VERSION@|${SLF4J_VERSION}|g" \
        "${HERE}/plugin.xml.in" > "${stage}/plugin.xml"

    # Every placeholder has to have been replaced. A leftover @NAME@ makes
    # MirthLauncher log "could not locate library" and carry on, so the extension
    # loads with a jar missing and fails later, at the one destination that
    # needed it -- which is a far worse failure than not building.
    if grep -q '@[A-Z_]*@' "${stage}/plugin.xml"; then
        grep -o '@[A-Z_]*@' "${stage}/plugin.xml" | sort -u >&2
        die "plugin.xml still contains unsubstituted placeholders"
    fi

    # And it has to parse. MirthLauncher reads it before the engine starts and,
    # on a parse error, logs one line and carries on without the extension --
    # after which everything downstream looks like it simply is not installed.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        cat > /tmp/ValidateXml.java <<JAVA
import javax.xml.parsers.DocumentBuilderFactory;
public class ValidateXml {
    public static void main(String[] args) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new java.io.File(args[0]));
    }
}
JAVA
        java /tmp/ValidateXml.java build/stage/backup/plugin.xml
    ' || die "plugin.xml does not parse; the engine would refuse the extension"

    # The console UI half travels inside the extension, so one install delivers
    # both (the pattern documented in the web administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    # Keep the version in the console manifest in step with plugin.xml: the
    # console shows it on the Extensions page, and two versions for one
    # extension is the kind of small lie that costs an hour later.
    sed -i.bak "s|\"version\": \"[^\"]*\"|\"version\": \"${PLUGIN_VERSION}\"|" \
        "${stage}/webadmin/plugin.json"
    rm -f "${stage}/webadmin/plugin.json.bak"

    local zip="${DIST}/backup-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/backup-${PLUGIN_VERSION}.zip backup"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
fetch_maven_deps
compile
selftest
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/backup-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "Backup"

Nothing is scheduled until it is configured. Open Backup in the console, choose
what to back up and where to, press Test destination, then Back up now -- and
only then turn the schedule on, so the first unattended run is not the first one
that has ever been tried.
EOF
