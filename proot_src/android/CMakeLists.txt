cmake_minimum_required(VERSION 3.22)

project(proot_android LANGUAGES C ASM)

if(NOT ANDROID)
    message(FATAL_ERROR "This build must use the Android NDK toolchain file")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(PROOT_LOADER_ADDRESS 0x2000000000)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(PROOT_LOADER_ADDRESS 0x600000000000)
else()
    message(FATAL_ERROR "Supported Android ABIs: arm64-v8a, x86_64")
endif()

get_filename_component(PROOT_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../src" REALPATH)
set(TALLOC_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../talloc-2.5.0" CACHE PATH
    "Path to the unpacked talloc 2.5.0 source tree")
get_filename_component(TALLOC_SOURCE_DIR "${TALLOC_SOURCE_DIR}" REALPATH)
set(PROOT_VERSION "5.1.107.91-lyra.2" CACHE STRING "Version embedded in proot --version")

if(NOT EXISTS "${TALLOC_SOURCE_DIR}/talloc.c" OR
   NOT EXISTS "${TALLOC_SOURCE_DIR}/talloc.h" OR
   NOT EXISTS "${TALLOC_SOURCE_DIR}/lib/replace/replace.h")
    message(FATAL_ERROR "Talloc 2.5.0 sources were not found at ${TALLOC_SOURCE_DIR}")
endif()

configure_file(build.h.in "${CMAKE_CURRENT_BINARY_DIR}/generated/build.h" @ONLY)

add_library(talloc_android STATIC
    "${TALLOC_SOURCE_DIR}/talloc.c"
    "${CMAKE_CURRENT_LIST_DIR}/talloc_android_compat.c"
)
set_target_properties(talloc_android PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(talloc_android PUBLIC
    "${TALLOC_SOURCE_DIR}"
    "${TALLOC_SOURCE_DIR}/lib/replace"
)

# talloc normally gets these portability results from Samba's Waf configure.
# They are fixed here to Android API 24/Bionic so the dependency stays static
# and the build remains reproducible without a host-side Waf installation.
target_compile_definitions(talloc_android PRIVATE
    NO_CONFIG_H
    __STDC_WANT_LIB_EXT1__=1
    TALLOC_BUILD_VERSION_MAJOR=2
    TALLOC_BUILD_VERSION_MINOR=5
    TALLOC_BUILD_VERSION_RELEASE=0
    STDC_HEADERS=1
    HAVE_STDBOOL_H=1
    HAVE_BOOL=1
    HAVE_INTPTR_T=1
    HAVE_UINTPTR_T=1
    HAVE_PTRDIFF_T=1
    HAVE_VOLATILE=1
    HAVE_UNISTD_H=1
    HAVE_STRING_H=1
    HAVE_STRINGS_H=1
    HAVE_SYS_TYPES_H=1
    HAVE_STDINT_H=1
    HAVE_INTTYPES_H=1
    HAVE_LIMITS_H=1
    HAVE_SYS_AUXV_H=1
    HAVE_GETAUXVAL=1
    HAVE_STRERROR=1
    HAVE_STRDUP=1
    HAVE_MEMMOVE=1
    HAVE_MEMMEM=1
    HAVE_MEMALIGN=1
    HAVE_MKTIME=1
    HAVE_TIMEGM=1
    HAVE_UTIME=1
    HAVE_UTIMES=1
    HAVE_STRLCPY=1
    HAVE_STRLCAT=1
    HAVE_SNPRINTF=1
    HAVE_VSNPRINTF=1
    HAVE_C99_VSNPRINTF=1
    HAVE_ASPRINTF=1
    HAVE_VASPRINTF=1
    HAVE_FTRUNCATE=1
    HAVE_FDATASYNC=1
    HAVE_DECL_FDATASYNC=1
    HAVE_POLL=1
    HAVE_USLEEP=1
    HAVE_VA_COPY=1
    HAVE___VA_COPY=1
    HAVE_CONSTRUCTOR_ATTRIBUTE=1
    HAVE_VISIBILITY_ATTR=1
    HAVE_ERRNO_DECL=1
    HAVE_DECL_EWOULDBLOCK=1
    HAVE_FUNCTION_MACRO=1
    HAVE_func_MACRO=1
    HAVE_DLFCN_H=1
    HAVE_DLOPEN=1
    HAVE_DLSYM=1
    HAVE_CHOWN=1
    HAVE_CHROOT=1
    HAVE_CONNECT=1
    HAVE_DUP2=1
    HAVE_LCHOWN=1
    HAVE_LINK=1
    HAVE_READLINK=1
    HAVE_REALPATH=1
    HAVE_SETENV=1
    HAVE_SETENV_DECL=1
    HAVE_SETEGID=1
    HAVE_SETEUID=1
    HAVE_SYMLINK=1
    HAVE_UNSETENV=1
    HAVE_STRNDUP=1
    HAVE_STRNLEN=1
    HAVE_STRSEP=1
    HAVE_STRTOK_R=1
    HAVE_STRTOLL=1
    HAVE_STRTOULL=1
    HAVE_PREAD=1
    HAVE_PWRITE=1
)
target_compile_options(talloc_android PRIVATE
    -O2
    -fvisibility=hidden
    "-ffile-prefix-map=${TALLOC_SOURCE_DIR}=./talloc-2.5.0"
)

add_executable(proot_loader
    "${PROOT_SOURCE_DIR}/loader/loader.c"
    "${PROOT_SOURCE_DIR}/loader/assembly.S"
)
set_target_properties(proot_loader PROPERTIES
    OUTPUT_NAME "libproot_loader"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/out"
)
target_include_directories(proot_loader PRIVATE "${PROOT_SOURCE_DIR}")
target_compile_definitions(proot_loader PRIVATE _FILE_OFFSET_BITS=64 _GNU_SOURCE)
target_compile_options(proot_loader PRIVATE
    -O2
    -fPIC
    -ffreestanding
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
)
target_link_options(proot_loader PRIVATE
    -static
    -nostdlib
    -Wl,--no-gc-sections
    -Wl,--build-id=none
    -Wl,-Ttext=${PROOT_LOADER_ADDRESS}
    -Wl,--rosegment
    -Wl,-z,noexecstack
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LOADER_INFO_C "${CMAKE_CURRENT_BINARY_DIR}/generated/loader-info.c")
    add_custom_command(
        OUTPUT "${LOADER_INFO_C}"
        COMMAND "${CMAKE_COMMAND}"
            -DNM_TOOL=${CMAKE_NM}
            -DLOADER_FILE=$<TARGET_FILE:proot_loader>
            -DOUTPUT_FILE=${LOADER_INFO_C}
            -P "${CMAKE_CURRENT_LIST_DIR}/GenerateLoaderInfo.cmake"
        DEPENDS proot_loader "${CMAKE_CURRENT_LIST_DIR}/GenerateLoaderInfo.cmake"
        VERBATIM
    )
endif()

set(PROOT_SOURCES
    cli/cli.c
    cli/proot.c
    cli/note.c
    execve/enter.c
    execve/exit.c
    execve/shebang.c
    execve/elf.c
    execve/ldso.c
    execve/auxv.c
    execve/aoxp.c
    path/binding.c
    path/glue.c
    path/canon.c
    path/f2fs-bug.c
    path/path.c
    path/proc.c
    path/temp.c
    syscall/seccomp.c
    syscall/syscall.c
    syscall/chain.c
    syscall/enter.c
    syscall/exit.c
    syscall/sysnum.c
    syscall/socket.c
    syscall/heap.c
    syscall/rlimit.c
    syscall/pipe_shadow.c
    tracee/tracee.c
    tracee/mem.c
    tracee/reg.c
    tracee/event.c
    tracee/seccomp.c
    tracee/statx.c
    ptrace/ptrace.c
    ptrace/user.c
    ptrace/wait.c
    extension/extension.c
    extension/ashmem_memfd/ashmem_memfd.c
    extension/kompat/kompat.c
    extension/fake_id0/chown.c
    extension/fake_id0/chroot.c
    extension/fake_id0/getsockopt.c
    extension/fake_id0/sendmsg.c
    extension/fake_id0/socket.c
    extension/fake_id0/open.c
    extension/fake_id0/unlink.c
    extension/fake_id0/rename.c
    extension/fake_id0/chmod.c
    extension/fake_id0/utimensat.c
    extension/fake_id0/access.c
    extension/fake_id0/exec.c
    extension/fake_id0/link.c
    extension/fake_id0/symlink.c
    extension/fake_id0/mk.c
    extension/fake_id0/stat.c
    extension/fake_id0/helper_functions.c
    extension/fake_id0/fake_id0.c
    extension/hidden_files/hidden_files.c
    extension/mountinfo/mountinfo.c
    extension/port_switch/port_switch.c
    extension/sysvipc/sysvipc.c
    extension/sysvipc/sysvipc_msg.c
    extension/sysvipc/sysvipc_sem.c
    extension/sysvipc/sysvipc_shm.c
    extension/link2symlink/link2symlink.c
    extension/fix_symlink_size/fix_symlink_size.c
)
list(TRANSFORM PROOT_SOURCES PREPEND "${PROOT_SOURCE_DIR}/")

add_executable(proot_exec ${PROOT_SOURCES} ${LOADER_INFO_C})
set_target_properties(proot_exec PROPERTIES
    OUTPUT_NAME "libproot_exec"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/out"
)
target_include_directories(proot_exec PRIVATE
    "${PROOT_SOURCE_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)
target_compile_definitions(proot_exec PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    ARG_MAX=131072
    PROOT_UNBUNDLE_LOADER=1
)
target_compile_options(proot_exec PRIVATE
    -O2
    -Wall
    -Wextra
    -fPIE
    "-ffile-prefix-map=${PROOT_SOURCE_DIR}=."
)
target_link_options(proot_exec PRIVATE
    -Wl,--as-needed
    -Wl,--build-id=none
    -Wl,-z,noexecstack
)
target_link_libraries(proot_exec PRIVATE talloc_android)

set(PROOT_DIST_DIR "${CMAKE_CURRENT_BINARY_DIR}/dist")
set(PROOT_DIST_EXEC "${PROOT_DIST_DIR}/libproot_exec.so")
set(PROOT_DIST_LOADER "${PROOT_DIST_DIR}/libproot_loader.so")
add_custom_command(
    OUTPUT "${PROOT_DIST_EXEC}" "${PROOT_DIST_LOADER}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PROOT_DIST_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_FILE:proot_exec> "${PROOT_DIST_EXEC}"
    COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_FILE:proot_loader> "${PROOT_DIST_LOADER}"
    COMMAND "${CMAKE_STRIP}" --strip-unneeded "${PROOT_DIST_EXEC}"
    COMMAND "${CMAKE_STRIP}" --strip-unneeded "${PROOT_DIST_LOADER}"
    DEPENDS proot_exec proot_loader
    VERBATIM
)
add_custom_target(proot_android_artifacts ALL
    DEPENDS "${PROOT_DIST_EXEC}" "${PROOT_DIST_LOADER}")
