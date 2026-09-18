#!/bin/sh
# Compile everything (including tests) and run the solver test suite
set -e
cd "$(dirname "$0")"
mkdir -p java/classes
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
if ! command -v "$JAVAC" >/dev/null 2>&1; then
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then JAVAC="$JAVA_HOME/bin/javac"; fi
fi
"$JAVAC" -d java/classes java/src/core/*.java java/src/app/*.java java/test/core/*.java
"$JAVA" -cp java/classes core.AllTests
