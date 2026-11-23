#!/usr/bin/env bash
set -e
cd -- "$(dirname -- "$0")"
if [ ! -f out/com/studyhub/server/StudyHubServerMain.class ]; then
    bash build.sh
fi
exec java -cp out com.studyhub.server.StudyHubServerMain "$@"
