#!/bin/sh
#
# run_host_test.sh - build and run the rfax_bridge decoder on the host.
#
# Mirrors the CMake source composition but targets the host compiler, so a
# rtl_433 submodule update can be checked without an Android device:
#
#   ./run_host_test.sh
#   ./run_host_test.sh capture.cu8 433920000 250000 1
#
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

CPP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RTL433="$CPP_DIR/rtl_433"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

if [ ! -f "$RTL433/src/rtl_433.c" ]; then
    echo "rtl_433 submodule missing; run: git submodule update --init --recursive" >&2
    exit 1
fi

# Same generated copy as CMake: append the embedding hook to rtl_433.c.
cat "$RTL433/src/rtl_433.c" "$CPP_DIR/rfax_feed_hook.inc" > "$OUT/rtl_433_embedded.c"

CFLAGS="-O2 -w -I $RTL433/include -I $RTL433 -I $RTL433/src -I $CPP_DIR"

for f in "$RTL433"/src/*.c; do
    base=$(basename "$f" .c)
    [ "$base" = "rtl_433" ] && continue
    if [ "$base" = "output_rtltcp" ]; then
        # Bionic compatibility: redirect pthread_cancel to the shim (same as CMake).
        gcc $CFLAGS -Dpthread_cancel=rfax_pthread_cancel \
            -include "$CPP_DIR/rfax_bionic_compat.h" -c "$f" -o "$OUT/$base.o"
    else
        gcc $CFLAGS -c "$f" -o "$OUT/$base.o"
    fi
done

# rtl_433.c defines main(); rename it for the host test executable.
gcc $CFLAGS -Dmain=rtl433_embedded_main -c "$OUT/rtl_433_embedded.c" -o "$OUT/rtl_433_embedded.o"
for f in "$RTL433"/src/devices/*.c; do
    base=$(basename "$f" .c)
    gcc $CFLAGS -c "$f" -o "$OUT/dev_$base.o"
done

gcc $CFLAGS -c "$CPP_DIR/rfax_bridge.c" -o "$OUT/rfax_bridge.o"
gcc $CFLAGS -c "$CPP_DIR/rfax_channel.c" -o "$OUT/rfax_channel.o"
gcc $CFLAGS -c "$CPP_DIR/rfax_bionic_compat.c" -o "$OUT/rfax_bionic_compat.o"
gcc $CFLAGS -c "$CPP_DIR/test/rfax_smoke.c" -o "$OUT/rfax_smoke.o"

gcc "$OUT"/*.o -lm -lpthread -o "$OUT/rfax_smoke"

# Channel filter unit test (links only the channel module).
gcc $CFLAGS -c "$CPP_DIR/test/rfax_channel_test.c" -o "$OUT/rfax_channel_test.o"
gcc "$OUT/rfax_channel_test.o" "$OUT/rfax_channel.o" -lm -o "$OUT/rfax_channel_test"
echo "=== running channel filter test ==="
"$OUT/rfax_channel_test"

echo "=== running host smoke test ==="
exec "$OUT/rfax_smoke" "$@"
