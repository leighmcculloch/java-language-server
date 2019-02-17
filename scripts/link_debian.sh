#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Needed if you have a java version other than 11 as default
JAVA_HOME='/usr/lib/jvm/java-11-openjdk-amd64'

# Compile sources
mvn compile

# Patch gson
if [ ! -e modules/gson.jar ]; then
  ./scripts/patch_gson.sh
fi

# Build using jlink
rm -rf dist/debian
$JAVA_HOME/bin/jlink \
  --module-path modules/gson.jar:target/classes \
  --add-modules gson,javacs \
  --launcher launcher=javacs/org.javacs.Main \
  --output dist/debian \
  --compress 2
