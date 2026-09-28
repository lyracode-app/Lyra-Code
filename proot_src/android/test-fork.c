#include <pthread.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static int wait_ok(pid_t pid)
{
    int status;
    return pid > 0 && waitpid(pid, &status, 0) == pid &&
        WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static void *spawn_many(void *unused)
{
    (void)unused;
    for (int i = 0; i < 20; ++i) {
        pid_t pid;
        char *args[] = {"/system/bin/sh", "-c", "exit 0", NULL};
        if (posix_spawn(&pid, args[0], NULL, NULL, args, environ) != 0 || !wait_ok(pid))
            return (void *)1;
    }
    return NULL;
}

int main(void)
{
    pid_t pid = fork();
    if (pid == 0) _exit(0);
    if (!wait_ok(pid)) return 1;
    pid = vfork();
    if (pid == 0) _exit(0);
    if (!wait_ok(pid) || system("exit 0") != 0) return 2;
    pthread_t threads[4];
    for (int i = 0; i < 4; ++i)
        if (pthread_create(&threads[i], NULL, spawn_many, NULL) != 0) return 3;
    for (int i = 0; i < 4; ++i) {
        void *result;
        if (pthread_join(threads[i], &result) != 0 || result != NULL) return 4;
    }
    puts("FORK_SPAWN_THREADS_OK");
    return 0;
}
