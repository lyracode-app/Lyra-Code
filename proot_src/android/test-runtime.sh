#!/system/bin/sh
# Run in an isolated adb shell directory containing the built runtime and helpers.
set -eu
cd "$(dirname "$0")"
BASE=$(pwd)
export PROOT_LOADER="$BASE/libproot_loader.so"
export PROOT_TMP_DIR="$BASE"
P="$BASE/libproot_exec.so"
"$P" --version
for MODE in seccomp no-seccomp; do
    if [ "$MODE" = no-seccomp ]; then export PROOT_NO_SECCOMP=1; else unset PROOT_NO_SECCOMP; fi
    CASE="$BASE/$MODE"
    mkdir -p "$CASE/lib" "$CASE/bin" "$CASE/links"
    export PROOT_L2S_DIR="$CASE/links"
    cp "$BASE/puts_proc_self_exe" "$CASE/lib/original"
    "$P" -l /system/bin/ln "$CASE/lib/original" "$CASE/lib/link"
    # Verify that this exercises a PRoot-faked hard link.
    test -L "$CASE/lib/link"
    ln -s ../lib/link "$CASE/bin/symlink"
    for NAME in "$CASE/lib/link" "$CASE/bin/symlink"; do
        test "$("$P" -l "$NAME")" = "$CASE/lib/link"
        test "$("$P" -l /system/bin/sh -c "$NAME")" = "$CASE/lib/link"
        test "$("$P" -l "$BASE/execveat" "$NAME")" = "$CASE/lib/link"
    done
    test "$("$P" -l "$CASE/lib/original")" = "$CASE/lib/original"
    test "$("$P" -l "$BASE/open_nofollow" "$CASE/lib/link")" = "$CASE/lib/link"
    test "$("$P" -l "$BASE/open_nofollow" "$CASE/lib/link" path)" = "$CASE/lib/link"
    "$P" -l "$BASE/test-fork"
    echo "$MODE: LINK_EXE_EXECVEAT_NOFOLLOW_FORK_OK"
done
# Exercise Lyra's sibling-loader discovery without PROOT_LOADER.
unset PROOT_LOADER PROOT_NO_SECCOMP
"$P" -l /system/bin/sh -c 'echo SIBLING_LOADER_OK'
