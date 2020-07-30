/*
 * Copyright (c) 2008, 2019, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.bench.jdk.incubator.foreign.nio.support;

import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;

/**
 * Unix implementation of PosixFileAttributes.
 */

public class UnixFileAttributes {
    private int     st_mode;
    private long    st_ino;
    private long    st_dev;
    private long    st_rdev;
    private int     st_nlink;
    private int     st_uid;
    private int     st_gid;
    private long    st_size;
    private long    st_atime_sec;
    private long    st_atime_nsec;
    private long    st_mtime_sec;
    private long    st_mtime_nsec;
    private long    st_ctime_sec;
    private long    st_ctime_nsec;
    private long    st_birthtime_sec;

    UnixFileAttributes() {
        super();
    }

    UnixFileAttributes(LibC.stat64 buf) {
        st_mode = buf.st_mode$get();
        st_ino = buf.st_ino$get();
        st_dev = buf.st_dev$get();
        st_rdev = buf.st_rdev$get();
        st_nlink = buf.st_nlink$get();
        st_uid = buf.st_uid$get();
        st_gid = buf.st_gid$get();
        st_size = buf.st_size$get();
        st_atime_sec = buf.st_atimespec$get().tv_sec$get();
        st_mtime_sec = buf.st_mtimespec$get().tv_sec$get();
        st_ctime_sec = buf.st_ctimespec$get().tv_sec$get();
        st_atime_nsec = buf.st_atimespec$get().tv_nsec$get();
        st_mtime_nsec = buf.st_mtimespec$get().tv_nsec$get();
        st_ctime_nsec = buf.st_ctimespec$get().tv_nsec$get();
        st_birthtime_sec = buf.st_birthtimespec$get().tv_sec$get();
    }

    UnixFileAttributes(MemorySegment buf) {
        try {
            st_mode = MemoryAccess.getIntAtOffset(buf, LibC.stat64.st_mode$OFFSET);
            st_ino = MemoryAccess.getLongAtOffset(buf, LibC.stat64.st_ino$OFFSET);
            st_dev = MemoryAccess.getLongAtOffset(buf, LibC.stat64.st_dev$OFFSET);
            st_rdev = MemoryAccess.getLongAtOffset(buf, LibC.stat64.st_rdev$OFFSET);
            st_nlink = MemoryAccess.getIntAtOffset(buf, LibC.stat64.st_nlink$OFFSET);
            st_uid = MemoryAccess.getIntAtOffset(buf, LibC.stat64.st_uid$OFFSET);
            st_gid = MemoryAccess.getIntAtOffset(buf, LibC.stat64.st_gid$OFFSET);
            st_size = MemoryAccess.getLongAtOffset(buf, LibC.stat64.st_size$OFFSET);
            long offset = LibC.stat64.st_atimespec$OFFSET;
            st_atime_sec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_sec$OFFSET);
            st_atime_nsec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_nsec$OFFSET);

            offset = LibC.stat64.st_mtimespec$OFFSET;
            st_mtime_sec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_sec$OFFSET);
            st_mtime_nsec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_nsec$OFFSET);

            offset = LibC.stat64.st_ctimespec$OFFSET;
            st_ctime_sec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_sec$OFFSET);
            st_ctime_nsec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_nsec$OFFSET);

            offset = LibC.stat64.st_birthtimespec$OFFSET;
            st_birthtime_sec = MemoryAccess.getLongAtOffset(buf, offset + LibC.timespec.tv_sec$OFFSET);
        } catch (Throwable ex) {
            throw new AssertionError(ex);
        }
    }

    public static UnixFileAttributes from(MemorySegment buf) {
        return new UnixFileAttributes(buf);
    }

    // package-private
    public int mode()  { return st_mode; }
    public long ino()  { return st_ino; }
    public long dev()  { return st_dev; }
    public long rdev() { return st_rdev; }
    public int nlink() { return st_nlink; }
    public int uid()   { return st_uid; }
    public int gid()   { return st_gid; }

    private static FileTime toFileTime(long sec, long nsec) {
        if (nsec == 0) {
            return FileTime.from(sec, TimeUnit.SECONDS);
        } else {
            try {
                long nanos = Math.addExact(nsec,
                    Math.multiplyExact(sec, 1_000_000_000L));
                return FileTime.from(nanos, TimeUnit.NANOSECONDS);
            } catch (ArithmeticException ignore) {
                // truncate to microseconds if nanoseconds overflow
                long micro = sec*1_000_000L + nsec/1_000L;
                return FileTime.from(micro, TimeUnit.MICROSECONDS);
            }
        }
    }

    public FileTime ctime() {
        return toFileTime(st_ctime_sec, st_ctime_nsec);
    }

    public FileTime lastModifiedTime() {
        return toFileTime(st_mtime_sec, st_mtime_nsec);
    }

    public FileTime lastAccessTime() {
        return toFileTime(st_atime_sec, st_atime_nsec);
    }

    public FileTime creationTime() {
        return FileTime.from(st_birthtime_sec, TimeUnit.SECONDS);
    }

    public long size() {
        return st_size;
    }
}
