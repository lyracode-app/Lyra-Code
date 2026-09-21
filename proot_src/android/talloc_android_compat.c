#include <stddef.h>

/* Android API 24 has no memset_explicit().  Talloc's replace.h redirects the
 * call to this symbol when HAVE_MEMSET_EXPLICIT is intentionally left unset. */
void *rep_memset_explicit(void *block, int value, size_t size)
{
    volatile unsigned char *cursor = block;

    while (size-- > 0) {
        *cursor++ = (unsigned char) value;
    }

    return block;
}
