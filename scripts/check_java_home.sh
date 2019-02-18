#!/bin/bash
# Check the version of java pointed to by JAVA_HOME in version 11.

set -e

if [[ -z "${JAVA_HOME}" ]]; then
  echo "error: JAVA_HOME must be set"
  exit 1
fi

echo "JAVA_HOME is set to: $JAVA_HOME"

java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n ';s/.* version "\(.*\)\.\(.*\)\..*".*/\1/p;')
echo "JAVA_HOME version is: $java_version"
if [ "$java_version" -ne 11 ]; then
  echo "error: JAVA_HOME must be set to a JDK version 11"
  exit 1
fi
