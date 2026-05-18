#!/usr/bin/env zsh
set -euo pipefail

cd -- "${0:A:h}"

exec sh ./run "$@"
