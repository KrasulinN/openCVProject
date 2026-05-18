#!/usr/bin/env zsh
set -euo pipefail

cd -- "${0:A:h}"

mvn -q -DskipTests compile exec:java -Dexec.mainClass=org.example.controller.Main