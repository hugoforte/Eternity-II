#!/bin/sh
# Build if needed, then start the web app
set -e
cd "$(dirname "$0")"
if [ ! -f java/classes/core/MrvSolver.class ]; then
  sh build.sh
fi
PY="${PYTHON:-python3}"
exec "$PY" server/app.py "$@"
