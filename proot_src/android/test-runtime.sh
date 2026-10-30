#!/system/bin/sh
# Run in an isolated adb shell directory containing the built runtime and helpers.
set -eu
trap 'echo "Runtime regression failed at line $LINENO" >&2' ERR
cd "$(dirname "$0")"
BASE=$(pwd)
export PROOT_LOADER="${PROOT_LOADER:-$BASE/libproot_loader.so}"
export PROOT_TMP_DIR="$BASE"
P="${PROOT_EXEC:-$BASE/libproot_exec.so}"
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
    "$P" -l "$BASE/test-f6b33793"
    echo "$MODE: LINK_EXE_EXECVEAT_NOFOLLOW_FORK_OK"

    # Exercise raw fork, including the child's preserved argument registers.
    STATUS=0
    "$P" -l "$BASE/fork_regs" || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP raw fork (ABI has no fork syscall)"
    else
        test "$STATUS" = 0
        # Also force SIGSYS in adb-shell contexts without the app's policy.
        /system/bin/linker64 "$BASE/fork_regs" trap "$P" -l "$BASE/fork_regs"
        echo "$MODE: FORK_REGISTERS_OK"
    fi

    ROOT="$CASE/rootfs"
    mkdir -p "$ROOT/bin" "$ROOT/work" "$ROOT/tmp"
    # Match the app: keep backing links beside files inside the guest rootfs.
    unset PROOT_L2S_DIR
    cp "$BASE/test-upstream" "$BASE/test-latest" "$BASE/test-571fb953" "$BASE/fchmodat2" "$BASE/open_tree" "$BASE/getdents" "$BASE/link" "$BASE/renameat2" "$BASE/statx" "$BASE/unix_socket" "$ROOT/bin/"
    guest() {
        "$P" -r "$ROOT" -b /system -b /apex -b /proc -b /dev -w /work "$@"
    }
    guest -0 /bin/test-upstream check 0 0
    guest -i 123456:234567 /bin/test-upstream check 123456 234567
    guest /bin/test-571fb953
    guest -0 /bin/test-571fb953

    # Ensure AT_EMPTY_PATH changed the guest cwd, leaving the launch cwd alone.
    test "$(stat -c %Y "$BASE")" != 123456789
    test "$(stat -c %Y "$ROOT/work")" = 123456789

    # These checks create files and therefore change the working directory mtime.
    guest -0 -l --sysvipc /bin/test-latest 0 0
    guest -i 123456:234567 -l --sysvipc /bin/test-latest 123456 234567

    # SO_PEERCRED must translate peers in another PRoot instance.
    guest -0 /bin/test-upstream server /work/peer 0 0 &
    SERVER=$!
    ATTEMPTS=0
    until [ -S "$ROOT/work/peer" ]; do
        ATTEMPTS=$((ATTEMPTS + 1))
        test "$ATTEMPTS" -lt 100
        sleep 0.05
    done
    guest -i 123456:234567 /bin/test-upstream client /work/peer 123456 234567
    wait "$SERVER"

    # All fd-mount entry points must return ENOSYS.
    for CALL in fsopen fsconfig fsmount fspick move_mount mount_setattr open_tree_attr; do
        guest /bin/open_tree -m "$CALL"
        guest -0 /bin/open_tree -m "$CALL"
    done
    guest /bin/open_tree -c / ENOSYS
    guest -0 /bin/open_tree -c -e "" ENOSYS

    # Older kernels and Android's outer seccomp policy can omit these calls.
    STATUS=0
    guest /bin/open_tree -e -s /work "" ok || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP open_tree (kernel or outer seccomp policy)"
    else
        test "$STATUS" = 0
        guest /bin/open_tree -s / / ok
        guest -0 /bin/open_tree -d /work -s / ../../../../.. ok
    fi
    echo test > "$ROOT/work/file"
    ln -s file "$ROOT/work/link"
    STATUS=0
    guest /bin/fchmodat2 /work/file 600 ok || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP fchmodat2 (kernel or outer seccomp policy)"
    else
        test "$STATUS" = 0
        test "$(stat -c %a "$ROOT/work/file")" = 600
        guest /bin/fchmodat2 -d /work file 640 ok
        chmod 444 "$ROOT/work/file"
        guest -0 /bin/fchmodat2 -n /work/link 600 EOPNOTSUPP
        test "$(stat -c %a "$ROOT/work/file")" = 444
    fi
    echo "$MODE: OCTOBER_UPSTREAM_REGRESSIONS_OK"

    # A failed link/rename must preserve the source and its link count.
    echo original > "$ROOT/work/a"
    echo existing > "$ROOT/work/b"
    STATUS=0
    guest -l /bin/link /work/a /work/b || STATUS=$?
    test "$STATUS" = 17
    test "$(cat "$ROOT/work/a")" = original
    test "$("$P" -l /system/bin/stat -c %h "$ROOT/work/a")" = 1
    guest -l /bin/link /work/a /work/c
    STATUS=0
    guest -l /bin/renameat2 /work/c /work/b 1 || STATUS=$?
    test "$STATUS" = 1
    test "$("$P" -l /system/bin/stat -c %h "$ROOT/work/c")" = 2

    # Linking an ordinary symlink must copy the symlink, not move its target.
    ln -s a "$ROOT/work/symbolic"
    guest -l /bin/link /work/symbolic /work/symbolic-copy
    test "$(readlink "$ROOT/work/symbolic-copy")" = a
    test "$(cat "$ROOT/work/a")" = original

    # A failed guest translation must never touch an existing host path.
    echo outside > "$CASE/outside"
    STATUS=0
    guest -l /bin/link "$CASE/outside" /work/escaped || STATUS=$?
    test "$STATUS" = 2
    test ! -L "$CASE/outside"
    test "$(cat "$CASE/outside")" = outside

    # The link-count suffix grows past four digits without breaking the chain.
    FINAL=$(readlink "$ROOT/work/.l2s.a0001")
    mv "$FINAL" "${FINAL%.*}.9999"
    rm "$ROOT/work/.l2s.a0001"
    ln -s "${FINAL%.*}.9999" "$ROOT/work/.l2s.a0001"
    guest -l /bin/link /work/a /work/large-count
    test "$("$P" -l /system/bin/stat -c %h "$ROOT/work/large-count")" = 10000
    "$P" -l /system/bin/rm "$ROOT/work/large-count"
    test "$("$P" -l /system/bin/stat -c %h "$ROOT/work/a")" = 9999

    # Directory d_type must agree with lstat for simulated hard links.
    # Keep implementation-only intermediates outside the directory being listed.
    mkdir "$ROOT/work/listing"
    echo listing > "$ROOT/work/listing/file"
    touch "$ROOT/work/listing/.proot-hidden"
    ln -s file "$ROOT/work/listing/symlink"
    PROOT_L2S_DIR="$ROOT/tmp" guest -l /bin/link /work/listing/file /work/listing/hardlink
    PROOT_L2S_DIR="$ROOT/tmp" guest -l -H /bin/getdents /work/listing > "$CASE/getdents.log"
    grep -q '^getdents64 hardlink 8 8$' "$CASE/getdents.log"
    if grep -q ' .proot-hidden ' "$CASE/getdents.log"; then exit 1; fi
    STATUS=0
    guest -H /bin/getdents /work/b > "$CASE/getdents-error.log" || STATUS=$?
    test "$STATUS" = 1
    grep -q '^getdents64 error 20$' "$CASE/getdents-error.log"

    # Fake root must leave statx targets unchanged, also under outer SIGSYS.
    echo statx > "$ROOT/work/statx-target"
    chmod 440 "$ROOT/work/statx-target"
    test "$(guest -0 /bin/statx -c /work/b /work/statx-target)" = 440
    test "$(stat -c %a "$ROOT/work/statx-target")" = 440
    STATUS=0
    RESULT=$(/system/bin/linker64 "$BASE/statx" trap "$P" -0 -r "$ROOT" -b /system -b /apex -b /proc -b /dev -w /work /bin/statx -c /work/b /work/statx-target) || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP trapped statx (ENOSYS or unavailable outer filter)"
    else
        test "$STATUS" = 0
        test "$RESULT" = 440
    fi
    test "$(stat -c %a "$ROOT/work/statx-target")" = 440

    # Socket aliases must be removed when the PRoot session exits.
    mkdir "$CASE/socket-tmp" "$CASE/socket-guest"
    LONG=$(printf '%090d' 0)
    for SESSION in 1 2; do
        PROOT_TMP_DIR="$CASE/socket-tmp" "$P" -r "$ROOT" -b /system -b /apex -b /proc -b /dev -b "$CASE/socket-guest:/tmp" -w /work /bin/unix_socket "/tmp/$LONG"
    done
    test -z "$(ls -A "$CASE/socket-tmp")"

    # Failed startup must leave the contents of a bound directory intact.
    mkdir -p "$CASE/startup-bound/sub" "$CASE/startup-tmp/guest"
    echo keep > "$CASE/startup-bound/sub/file"
    printf '#!/nonexistent-lyra-interpreter\n' > "$CASE/bad-script"
    chmod 755 "$CASE/bad-script"
    for POLLUTE in glue placeholders; do
        if [ "$POLLUTE" = glue ]; then export PROOT_DONT_POLLUTE_ROOTFS=1; else unset PROOT_DONT_POLLUTE_ROOTFS; fi
        if PROOT_TMP_DIR="$CASE/startup-tmp" "$P" -b "$CASE/startup-bound:$CASE/startup-tmp/guest/missing/bound" "$CASE/bad-script"; then
            echo 'Unexpected successful startup' >&2
            exit 1
        fi
        test "$(cat "$CASE/startup-bound/sub/file")" = keep
    done
    unset PROOT_DONT_POLLUTE_ROOTFS

    # Command lookup must retain the guest spelling through overlapping binds.
    mkdir -p "$CASE/lookup/bin" "$CASE/lookup/lib" "$CASE/lookup/alias/bin"
    printf '#!/system/bin/sh\necho "$0"\n' > "$CASE/lookup/lib/program"
    chmod 755 "$CASE/lookup/lib/program"
    ln -s ../lib/program "$CASE/lookup/bin/program"
    test "$("$P" -b "$CASE/lookup/bin:$CASE/lookup/alias/bin" "$CASE/lookup/bin/program")" = "$CASE/lookup/bin/program"
    echo "$MODE: LYRA6_UPSTREAM_REGRESSIONS_OK"
done
# Exercise Lyra's sibling-loader discovery without PROOT_LOADER.
unset PROOT_LOADER PROOT_NO_SECCOMP
"$P" -l /system/bin/sh -c 'echo SIBLING_LOADER_OK'
