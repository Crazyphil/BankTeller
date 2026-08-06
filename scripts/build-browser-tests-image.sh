#!/usr/bin/env bash
# Build (or rebuild) the BankTeller browser-tests runner image.
#
# Idempotent: safe to run repeatedly. Podman is preferred (matches the main
# build); Docker works as a fallback if Podman is absent.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dockerfile="$repo_root/app/web/Dockerfile.browser-tests"
image_name="localhost/bankteller-browser-tests"
image_tag="latest"

# Prefer podman (the project's container engine); fall back to docker.
if command -v podman >/dev/null 2>&1; then
    runner="podman"
elif command -v docker >/dev/null 2>&1; then
    runner="docker"
else
    echo "ERROR: neither podman nor docker found on PATH" >&2
    exit 1
fi

echo "Building $image_name:$image_tag with $runner..."
"$runner" build -t "$image_name:$image_tag" -f "$dockerfile" "$repo_root/app/web"

echo ""
echo "Done. Image: $image_name:$image_tag"
echo ""
echo "Run browser tests via Gradle:"
echo "  ./gradlew :app:web:containerJsBrowserTest"
echo "  ./gradlew :app:web:containerWasmJsBrowserTest"
