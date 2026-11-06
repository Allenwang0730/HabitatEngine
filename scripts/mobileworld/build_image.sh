#!/usr/bin/env bash
# build_image.sh <MobileWorld checkout> [tag] : build the MobileWorld image used for Habitat runs (see Dockerfile.mw).
# Run in WSL/Linux as a user in the docker group. apt inside the build goes through $HTTP_PROXY if set.
set -euo pipefail
MW="${1:?usage: build_image.sh <MobileWorld checkout> [tag]}"
TAG="${2:-mobile_world:v1.2-local}"
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT
cp -r "$MW/src" "$CTX/src"
find "$CTX/src" -name __pycache__ -prune -exec rm -rf {} +
mkdir -p "$CTX/stage/usr/local/bin" "$CTX/stage/app/docker"
install -m 755 "$MW/docker/entrypoint.sh" "$CTX/stage/usr/local/bin/entrypoint.sh"
install -m 755 "$MW/docker/start_emulator.sh" "$MW/docker/proxy_chain.py" "$CTX/stage/app/docker/"
cp "$(dirname "$0")/Dockerfile.mw" "$CTX/Dockerfile"
PROXY_ARGS=()
[ -n "${HTTP_PROXY:-${http_proxy:-}}" ] && PROXY_ARGS=(--build-arg "http_proxy=${HTTP_PROXY:-$http_proxy}" --build-arg "https_proxy=${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-$http_proxy}}}")
docker build --network host "${PROXY_ARGS[@]}" -t "$TAG" "$CTX"
echo "built $TAG ($(cd "$MW" && git rev-parse --short HEAD))"
