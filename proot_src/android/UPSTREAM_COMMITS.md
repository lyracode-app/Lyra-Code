# Lyra .6 upstream commits

Integrated on main from 8bf810c through b27d81a, inclusive (40 commits).
The merge records the complete upstream history as its second parent.
Runtime and test changes are applied in upstream order as a net delta
from e2d843e; main had already integrated that baseline on a separate
history. Existing Lyra adaptations are preserved.

AGENTS.md is kept at its existing main version as required by the local
contributor policy. The upstream instruction-only commit ab7c1c5 is
recorded in the merge history without changing this file.

- `8bf810ccc433ff3e4d615467d5dec7118e3d6c92` compat: fix build with glibc 2.43
- `032f82d579c3fc45e111b04d6c9c50d38d629ecb` link2symlink: decrement link counts once the kernel removed the name
- `bd16a7d6b7462f754836c75503d12c00f9b770f5` link2symlink: ignore syscalls whose translation failed
- `bffb58da8b3a3765298d6cd82c3fdde97262249e` link2symlink: report the error a faked hard link failed with
- `87c4109a528e8eeec3259fd230b9f73f99c95367` link2symlink: leave broken faked hard links to the kernel
- `03aea836179e5fec9c4cb6ea8eb05a6145ef55b9` link2symlink: reserve the intermediate before moving the file
- `05a93edfc85fe53d5107f2f0b0cd21e31a7c61bb` link2symlink: read link counts of 10000 and more
- `fa47780ce5576eb22d2f779d700a44640d957529` link2symlink: let 9999 files of the same name be linked
- `1ce2525b3c30276ffd08fe35accc081921151918` link2symlink: give faked hard links the d_type of their file
- `355f840ea50c9756a2c74b94cf96e9010691cf57` hidden_files: leave failed getdents(2) alone
- `b8b3e6280bc52d5e61c78131bd5a33738714a3c1` hidden_files: parse the getdents(2) records of 32-bit tracees
- `968ae1be84c69a607dbcd43ee514ab04753d60c5` link2symlink: link symbolic links as symbolic links
- `1f25aa4565f9dd55cbc6c8cda00b58def4530e5c` fake_id0: leave the mode of statx(2) targets alone
- `a848b4d3ed4cb89be4332dd65212637781af88dd` seccomp: let statx(2) and statfs(2) emulated on SIGSYS be seen as such
- `41aefbb57192a58cc13a7dde0b3d0742468a0474` tests: let the empty-argv tests pass on Linux 5.18 and later
- `10142fe81b67d74e568e9c7956c3c157ad219455` tests: compare long shebang lines with the running kernel
- `abd681c591c981c048c5030a3982acae519ae15c` tests: sample the initial brk before stdio grows the heap
- `8177373e4f61452bdf699a998e331e304f646d67` tests: don't stop test-8d3c07f5 on the refused ln
- `84a584bb6f9882406ce97dcef18c2f0cf753db70` tests: make the climbing symlinks of test-hhhhhhhh without ln -r
- `ee6543334a67261d19e7023ae5eb30af5a7ad4f3` tests: remove all the temporary files of test-6b5a254a and test-aaaaaaaa
- `798b9900e8412afc99a1a7835e3e339a004f8af2` tests: run gdb in batch mode
- `00d00fdf1bfad780465fda8db74ddcb04135de93` tests: keep the temporary files of the suite in the repository
- `67ae659dd0fdc73d041c9999f13ba34ef6d0d51d` cli: let the parent remove the glue when the program can't be run
- `8a4484daa2e31614d52d9d3e715d680f99a0c8e6` tests: leave the system files of the host alone
- `1d0e732f1770d7f8dd1254f072c85da2060e5f7e` path: keep the guest path which() finds the program at
- `345dbd4a5f9822d20964cc32d49e966f86eccbfd` syscall: remove the sockets bound to a shorter host path at exit
- `8f1029094bea9b82c71d0fe928e39f95fa7aa58b` link2symlink: don't negate an unsigned index into a file name
- `c6880fcf8f4bbc85e200cc04589062754805fbc9` link2symlink: link the faked hard link linkat(AT_SYMLINK_FOLLOW) follows
- `be783d156fe28e1897a54a2d0b52439397275cf8` path: keep the final component of the command given to -q
- `e921710eb80a06d1dd5448212e580b0f5fd7d8c9` tests: let the suite run on Termux
- `3c6aa6f8ec7a3bb6dd7b5f694d065b34ff89dec1` tests: don't rely on /tmp nor LD_SHOW_AUXV on Android
- `79ed0997d7d2a4d330e4fd3f627884d8b9ebeda4` tests: include the headers GCC 14 and later require
- `0a829ba1a904a1164a3d6a309423edc28fd67611` tests: accept the AT_HWCAP recent versions of glibc print
- `fa7eda41b0c879020613662aba65dd7ca98a0f3b` tests: don't run uutils as the fake qemu of test-2401b850
- `9baa08b4b76d084edc3df73f7707bd3c4ad58d36` ci: run the test suite on Ubuntu 26.04 for amd64 and arm64
- `ab7c1c533d423ac91a658b752a5c263d53cbce9b` Update AGENTS.md
- `ef54aad14cda4b01f702557b39131163eae362d2` tracee: don't restart a held child that was killed meanwhile
- `37f5cef73d1d79e22c4f48b491e9a5c1bd16d8c3` tracee: don't register a child that died before its fork event
- `83c31d2d4099f75d225ea2bdc0f784920945cabf` tracee: hand the leader's PID over to a thread calling execve(2)
- `b27d81a68d765f3462e807f4a14e01a03f8e89ea` tracee: replace a leftover entry instead of aborting

# Lyra .5 upstream commits

Applied to main in this order, including both endpoints:

- `9688d01e5ab46e07ec8d207f9f470331e1fe2c3a` fake_id0: read unterminated result string by length
- `4a1dbac41fc25db96b7a6211e474b08bf93312ee` fake_id0: replay fstat for descriptors that aren't paths
- `7f2fab0fb3a9e15f63ac47497ebc29d72281206e` link2symlink: count faked hard links in the emulated fstat
- `0439248ae494c1b10c796e34d90ec497499c1aac` sysvipc: back shared memory with memfd where /dev/ashmem is closed
- `04c28b7dd1efed8de1df02a887f25d0d0ed8192c` fake_id0: keep the group when chown(2) passes -1 for it
- `77310de75b126b2667b652dfee7cde08f9ec4af2` fake_id0: apply chmod through /proc/self/fd to the open file's record
- `54733e84c38d12a0a163c68926e2392fa08cbb1a` fake_id0: apply chmod modes to the real file as well
- `ea0e9b6d41d340eebf896742604f6b40cf22ff0b` fake_id0: record only the permission bits of a chmod mode
- `fdbed740dfe4fbfe3669b3db2e62e42bfd72a9b1` fake_id0: record fchmodat2(fd, "", mode, AT_EMPTY_PATH)
- `788c48205be6199b5f2076ae5304277588f978b9` fake_id0: write the chmod record once the kernel has applied the mode
- `38a60cbed019a8f12e698f5fb8d869e83564ad2b` fake_id0: follow mode changes made through POSIX ACLs
- `1a41a193e809b53942a0c708540ab2962632d28f` fake_id0: compare statx's group with the real gid, not the uid
- `cf393e0ea3ee56421ff0becf5ef7901519a2a8f2` fake_id0: apply ownership records to statx
- `ccd4b745b474cb67dc0bcec8eeddd5667391dac9` fake_id0: apply ownership records to fstatat(fd, "", AT_EMPTY_PATH)
- `32d70f75743de0f76c19db123f4ee549fc4af5cb` fake_id0: keep the id mapping of fstatat(AT_EMPTY_PATH) on non-files
- `66b112ed38c6433d63f9e55e2d5fd5241fd75d5e` fake_id0: move ownership records on renameat2
- `53b2159cda491892168bc591084d7082f2ff270c` fake_id0: name the record of "dir/" after "dir"
- `e8a6c0ebe02a7acde4a3fa60399f1fc158d892f0` fake_id0: remove orphaned records along with their directory
- `4a27ca61ff7a800cdcac3bc616929bba7a484c74` fake_id0: move records once the kernel has renamed the file
- `9bd53079d25737ac739e1210cd490c49377d0cbe` seccomp: run fork(2) as clone(2) when the outer policy rejects it
- `e2d843ee6dab076ea0ed1ffd5d0ea12232185499` seccomp: give the child of fork(2) run as clone(2) its registers back
