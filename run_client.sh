#!/usr/bin/env bash
set -e
cd -- "$(dirname -- "$0")"
if [ ! -f out/com/studyhub/ui/StudyHubClientMain.class ]; then
    bash build.sh
fi
exec java -cp out com.studyhub.ui.StudyHubClientMain "$@"
