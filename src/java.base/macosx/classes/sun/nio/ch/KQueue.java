/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.internal.misc.Unsafe;
import jdk.internal.panama.LibC;
import jdk.internal.panama.LibC.kevent;
import jdk.internal.panama.LibC.timespec;
import sun.nio.FFIUtils;
import sun.nio.FFIUtils.Scope;

import static jdk.internal.panama.sys.errno_h.EINTR;
import static sun.nio.FFIUtils.localScope;

/**
 * Provides access to the BSD kqueue facility.
 */

class KQueue {
    private KQueue() { }

    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final MemoryAddress nullPtr = MemoryAddress.NULL;

    /**
     * struct kevent {
     *        uintptr_t       ident;          // identifier for this event, usually the fd
     *        int16_t         filter;         // filter for event
     *        uint16_t        flags;          // general flags
     *        uint32_t        fflags;         // filter-specific flags
     *        intptr_t        data;           // filter-specific data
     *        void            *udata;         // opaque user data identifier
     * };
     */
    private static final int SIZEOF_KQUEUEEVENT;
    private static final int OFFSET_IDENT;
    private static final int OFFSET_FILTER;
    private static final int OFFSET_FLAGS;

    // filters
    static final int EVFILT_READ  = -1;
    static final int EVFILT_WRITE = -2;

    // flags
    static final int EV_ADD     = 0x0001;
    static final int EV_DELETE  = 0x0002;
    static final int EV_ONESHOT = 0x0010;
    static final int EV_CLEAR   = 0x0020;

    /**
     * Allocates a poll array to handle up to {@code count} events.
     */
    static long allocatePollArray(int count) {
        return unsafe.allocateMemory(count * SIZEOF_KQUEUEEVENT);
    }

    /**
     * Free a poll array
     */
    static void freePollArray(long address) {
        unsafe.freeMemory(address);
    }

    /**
     * Returns kevent[i].
     */
    static long getEvent(long address, int i) {
        return address + (SIZEOF_KQUEUEEVENT*i);
    }

    /**
     * Returns the file descriptor from a kevent (assuming it is in the ident field)
     */
    static int getDescriptor(long address) {
        return unsafe.getInt(address + OFFSET_IDENT);
    }

    static short getFilter(long address) {
        return unsafe.getShort(address + OFFSET_FILTER);
    }

    static short getFlags(long address) {
        return unsafe.getShort(address + OFFSET_FLAGS);
    }

    static int create() throws IOException {
        int kqfd = LibC.kqueue();
        if (kqfd < 0) {
            throw new IOException("kqueue failed");
        }
        return kqfd;
    }

    static int register(int kqfd, int fd, int filter, int flags) {
        try (FFIUtils.Scope s = FFIUtils.localScope()) {
            kevent ev = kevent.allocate(s::allocate);
            ev.ident$set(fd);
            ev.filter$set((short) filter);
            ev.flags$set((short) flags);
            ev.fflags$set(0);
            ev.data$set(0);
            ev.udata$set(nullPtr);

            int rv, errno;
            do {
                rv = LibC.kevent(kqfd, ev.ptr(), 1, nullPtr, 0, nullPtr);
                if (rv == -1) {
                    errno = FFIUtils.errno();
                    if (errno != EINTR) {
                        return errno;
                    }
                }
            } while (rv == -1);
            return 0;
        }
    }

    static int poll(int kqfd, long pollAddress, int nevents, long timeout)
        throws IOException {
        try (Scope s = localScope()) {
            MemoryAddress tsp;
            if (timeout >= 0) {
                timespec ts = timespec.allocate(s::allocate);
                ts.tv_sec$set(timeout / 1000);
                ts.tv_nsec$set((timeout % 1000) * 1000_000);
                tsp = ts.ptr();
            } else {
                tsp = nullPtr;
            }
            int res = LibC.kevent(kqfd, nullPtr, 0,
                    MemoryAddress.ofLong(pollAddress),
                    nevents, tsp);

            if (res < 0) {
                int errno = FFIUtils.errno();
                if (errno == EINTR) {
                    return IOStatus.INTERRUPTED;
                } else {
                    throw new IOException("kqueue failed");
                }
            }
            return res;
        }
    }

    static {
        IOUtil.load();
        SIZEOF_KQUEUEEVENT = (int) kevent.sizeof();
        OFFSET_FILTER = (int) kevent.offsetof("filter");
        OFFSET_IDENT = (int) kevent.offsetof("ident");
        OFFSET_FLAGS = (int) kevent.offsetof("flags");
    }
}
