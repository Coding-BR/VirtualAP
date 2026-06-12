#!/bin/bash
# VirtualAP rootfs builder - trimmed from Droidspaces-rootfs-builder.
# Builds the minimal Alpine payload for a target arch (default: arm64 for phones).
set -e

: "${VERSION:=dev}"
DATE=$(date +%Y%m%d)
PLATFORM="linux/arm64"
ARCH_LABEL="aarch64"
DOCKERFILE="$(dirname "$0")/Alpine-VirtualAP.Dockerfile"

while getopts "a:v:" opt; do
  case $opt in
    a)
      case "$OPTARG" in
        aarch64|arm64) PLATFORM="linux/arm64"; ARCH_LABEL="aarch64" ;;
        x86_64|amd64)  PLATFORM="linux/amd64"; ARCH_LABEL="x86_64" ;;
        *) echo "Unsupported arch: $OPTARG (use aarch64 or x86_64)"; exit 1 ;;
      esac ;;
    v) VERSION="$OPTARG" ;;
    *) echo "Usage: $0 [-a <aarch64|x86_64>] [-v <version>]"; exit 1 ;;
  esac
done

OUT_DIR="$(dirname "$0")/../out"
mkdir -p "$OUT_DIR"

TEMP_TAR="$OUT_DIR/VirtualAP-rootfs.tar"
FINAL_NAME="$OUT_DIR/VirtualAP-rootfs-${ARCH_LABEL}-${DATE}-${VERSION}.tar.xz"

echo "========================================================="
echo " VirtualAP rootfs build"
echo " Platform : $PLATFORM"
echo " Version  : $VERSION"
echo "========================================================="

# Cross-arch builds need binfmt handlers registered once per boot
if [ "$PLATFORM" = "linux/arm64" ] && [ "$(uname -m)" != "aarch64" ]; then
    if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
        echo "Registering qemu binfmt handlers (one-time)..."
        docker run --privileged --rm tonistiigi/binfmt --install arm64
    fi
fi

if ! docker buildx inspect virtualap-builder >/dev/null 2>&1; then
    docker buildx create --name virtualap-builder --driver docker-container --use
else
    docker buildx use virtualap-builder
fi
docker buildx inspect --bootstrap >/dev/null

docker buildx build \
  --platform "$PLATFORM" \
  --target export \
  --output type=tar,dest="$TEMP_TAR" \
  -f "$DOCKERFILE" \
  "$(dirname "$0")"

# Extract the statically-linked busybox from the rootfs for host-side use
# (Android lacks unshare/nsenter; the chroot engine needs this binary)
BUSYBOX_DST="$(dirname "$0")/../backend/bin"
mkdir -p "$BUSYBOX_DST"
echo "Extracting busybox.static from rootfs tarball..."
tar -xf "$TEMP_TAR" -O bin/busybox.static > "$BUSYBOX_DST/busybox"
chmod +x "$BUSYBOX_DST/busybox"
echo "Extracted busybox to $BUSYBOX_DST/busybox ($(du -h "$BUSYBOX_DST/busybox" | cut -f1))"

echo "Compressing (xz -9, multi-threaded)..."
xz -T0 -9 -f "$TEMP_TAR"
mv "${TEMP_TAR}.xz" "$FINAL_NAME"

echo "========================================================="
echo " Done: $FINAL_NAME ($(du -h "$FINAL_NAME" | cut -f1))"
echo "========================================================="
