#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK 21 and nothing else - no network, no database, no Gradle.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
# Everything except the document store's Mongo implementation and its CLI:
# those need org.mongodb:mongodb-driver-sync on the classpath (see
# DocumentStoreCli's class doc for why they're split out), which is the one
# real external dependency in this project - everything selfCheck touches
# still builds from the JDK alone.
javac -d build/selfcheck $(find src/main/java -name '*.java' \
    ! -name 'MongoDocumentStore.java' ! -name 'DocumentStoreCli.java')

echo
echo "==> running"
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
