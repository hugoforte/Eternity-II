#!/bin/sh
# Compile the Java solver + engine into java/classes
set -e
cd "$(dirname "$0")"
mkdir -p java/classes
JAVAC="${JAVAC:-javac}"
if ! command -v "$JAVAC" >/dev/null 2>&1; then
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
  else
    echo "javac not found. Install a JDK 17+ or set JAVA_HOME." >&2
    exit 1
  fi
fi
echo "compiling with $JAVAC ..."
"$JAVAC" -d java/classes java/src/core/*.java java/src/app/*.java
echo "done -> java/classes"
