#!/usr/bin/env bash
#
# RePatch 2.0 container entrypoint — all-in-one interactive image.
#
# MySQL runs INSIDE this container (datadir in the data volume), then:
#   - with a TTY (docker run -it):   an interactive shell with the
#                                    `repatch` CLI on PATH
#   - with arguments:                that command is run verbatim
#                                    (e.g. `... repatch run 16954`)
#   - no TTY, no arguments:          one default sample run (CI mode;
#                                    honors the RP_* env contract in
#                                    bin/repatch-lib.sh)
set -uo pipefail
source /home/repatch/bin/repatch-lib.sh

start_mysql || exit 4

rc=0
if [ $# -gt 0 ]; then
  "$@"; rc=$?
elif [ -t 0 ]; then
  banner
  # First launch: ask for a GitHub token once; it persists in the data
  # volume (or comes from RP_GITHUB_TOKEN). Change it with 'repatch token'.
  if load_token; then
    token_status
  else
    prompt_token || true
  fi
  export RP_GITHUB_TOKEN="${RP_GITHUB_TOKEN:-}"
  cd "$HOME"
  bash -i; rc=0
else
  repatch run; rc=$?
fi

shutdown_mysql
exit $rc
