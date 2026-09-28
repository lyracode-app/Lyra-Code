/* Test-only Android LD_PRELOAD shim. Never package this in the application.
 * Simulate kernels returning zero for successful PTRACE_GETEVENTMSG requests.
 */
#include <dlfcn.h>
#include <stdarg.h>
#include <sys/ptrace.h>
#include <sys/types.h>

long ptrace(int request, ...)
{
    va_list args;
    va_start(args, request);
    pid_t pid = va_arg(args, pid_t);
    void *address = va_arg(args, void *);
    void *data = va_arg(args, void *);
    va_end(args);
    long (*real_ptrace)(int, ...) = dlsym(RTLD_NEXT, "ptrace");
    long result = real_ptrace(request, pid, address, data);
    if (request == PTRACE_GETEVENTMSG && result == 0 && data != NULL)
        *(unsigned long *)data = 0;
    return result;
}
