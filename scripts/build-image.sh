#!/usr/bin/env bash
# Build the BankTeller deployment image.
#
# Builds the fat JAR on the host (requires JDK 21 and libatomic1 on Linux),
# then builds the container image from the copy-only root Dockerfile.
# After a successful build, dangling images are pruned so repeated rebuilds
# do not accumulate stale layers.
#
# Podman is preferred (matches the browser-tests build); Docker works as a
# fallback if Podman is absent.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image_name="bankteller"
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

echo "Building fat JAR on host (requires JDK 21; on Debian/Ubuntu also: sudo apt-get install libatomic1)..."
"$repo_root/gradlew" :server:shadowJar --no-daemon

echo "Building $image_name:$image_tag with $runner..."
"$runner" build -t "$image_name:$image_tag" -f "$repo_root/Dockerfile" "$repo_root"

echo "Pruning dangling images..."
"$runner" image prune -f

echo ""
echo "Done. Image: $image_name:$image_tag"
