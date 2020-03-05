#define _ALLBSD_SOURCE
#define _DARWIN_C_SOURCE
#define _XOPEN_SOURCE

#include "unistd.h"
#include "sys/errno.h"
#include "sys/stat.h"
#include "fcntl.h"
#include "string.h"
#include "stdio.h"
#include "stdlib.h"
#include "sys/time.h"
#include "dirent.h"
#include "pwd.h"
#include "grp.h"
#include "sys/statvfs.h"
#include "string.h"
#include <sys/param.h>
#include <sys/mount.h>
#include <sys/event.h>

#include <pthread.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/uio.h>
