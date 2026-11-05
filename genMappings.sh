#!/bin/bash
# Build the mod and refresh packet mappings for the requested or supported versions.
exec python3 .github/scripts/generate-mappings.py "$@"
