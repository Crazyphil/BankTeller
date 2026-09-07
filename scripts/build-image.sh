#!/usr/bin/env bash
# Build the BankTeller deployment image.
#
# Runs the fat-JAR build on the host (reusing the ~/.gradle cache across
# rebuilds) and then assembles a copy-only runtime image. On success, dangling
# (untagged) images are pruned automatically so repeated rebuilds cannot
# re-accumulate unbounded overlay storage.
#
# Idempotent: safe to run repeatedly. Podman is preferred (the project's
# container engine); Docker works as a fallback if Podman is absent.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Tag matches the `image:` name in docker-compose.yml so `docker compose up`
# uses the pre-built image (podman stores it as localhost/bankteller:latest).
image_name="bankteller"
image_tag="latest"

# Build the fat JAR on the host so container layers don't churn with every
# source change. Requires JDK 21 and (on Debian/Ubuntu) libatomic1.
echo "Building fat JAR with Gradle..."
"$repo_root/gradlew" :server:shadowJar --no-daemon

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
"$runner" build -t "$image_name:$image_tag" -f "$repo_root/Dockerfile" "$repo_root"

# Remove dangling images created by rebuild churn. Only untagged, unreferenced
# layers are removed; running containers and tagged images are untouched.
"$runner" image prune -f

echo ""
echo "Done. Image: $image_name:$image_tag"
echo ""
echo "Run the app:"
echo "  docker compose up -d"
