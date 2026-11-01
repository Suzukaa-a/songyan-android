#include <stddef.h>
#include <errno.h>

// Engine scripts must not launch processes from the IME.
#define l_system(cmd) ((cmd) == NULL ? 0 : (errno = ENOSYS, -1))
