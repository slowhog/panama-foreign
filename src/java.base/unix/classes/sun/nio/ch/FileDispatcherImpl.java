/*
 * Copyright (c) 2000, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.VarHandle;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.panama.LibC;
import jdk.internal.panama.LibC.flock;
import jdk.internal.panama.LibC.stat64;
import jdk.internal.panama.LibC.statvfs;
import sun.nio.FFIUtils;

import static jdk.incubator.foreign.CSupport.C_INT;
import static jdk.internal.panama.stdio_h.SEEK_CUR;
import static jdk.internal.panama.stdio_h.SEEK_SET;
import static jdk.internal.panama.sys.errno_h.EACCES;
import static jdk.internal.panama.sys.errno_h.EAGAIN;
import static jdk.internal.panama.sys.errno_h.EINTR;
import static jdk.internal.panama.sys.errno_h.ENOTSUP;
import static jdk.internal.panama.sys.fcntl_h.F_FULLFSYNC;
import static jdk.internal.panama.sys.fcntl_h.F_NOCACHE;
import static jdk.internal.panama.sys.fcntl_h.F_RDLCK;
import static jdk.internal.panama.sys.fcntl_h.F_SETLK;
import static jdk.internal.panama.sys.fcntl_h.F_SETLKW;
import static jdk.internal.panama.sys.fcntl_h.F_UNLCK;
import static jdk.internal.panama.sys.fcntl_h.F_WRLCK;
import static jdk.internal.panama.sys.socket_h.AF_UNIX;
import static jdk.internal.panama.sys.socket_h.SOCK_STREAM;
import static sun.nio.FFIUtils.errno;

class FileDispatcherImpl extends FileDispatcher {

    private static int preCloseFD = -1;

    static {
        IOUtil.load();
        init();
    }

    private static final JavaIOFileDescriptorAccess fdAccess =
            SharedSecrets.getJavaIOFileDescriptorAccess();

    FileDispatcherImpl() {
    }

    int read(FileDescriptor fd, long address, int len) throws IOException {
        return read0(fd, address, len);
    }

    int pread(FileDescriptor fd, long address, int len, long position)
        throws IOException
    {
        return pread0(fd, address, len, position);
    }

    long readv(FileDescriptor fd, long address, int len) throws IOException {
        return readv0(fd, address, len);
    }

    int write(FileDescriptor fd, long address, int len) throws IOException {
        return write0(fd, address, len);
    }

    int pwrite(FileDescriptor fd, long address, int len, long position)
        throws IOException
    {
        return pwrite0(fd, address, len, position);
    }

    long writev(FileDescriptor fd, long address, int len)
        throws IOException
    {
        return writev0(fd, address, len);
    }

    long seek(FileDescriptor fd, long offset) throws IOException {
        return seek0(fd, offset);
    }

    int force(FileDescriptor fd, boolean metaData) throws IOException {
        return force0(fd, metaData);
    }

    int truncate(FileDescriptor fd, long size) throws IOException {
        return truncate0(fd, size);
    }

    long size(FileDescriptor fd) throws IOException {
        return size0(fd);
    }

    int lock(FileDescriptor fd, boolean blocking, long pos, long size,
             boolean shared) throws IOException
    {
        return lock0(fd, blocking, pos, size, shared);
    }

    void release(FileDescriptor fd, long pos, long size) throws IOException {
        release0(fd, pos, size);
    }

    void close(FileDescriptor fd) throws IOException {
        fdAccess.close(fd);
    }

    void preClose(FileDescriptor fd) throws IOException {
        preClose0(fd);
    }

    void dup(FileDescriptor fd1, FileDescriptor fd2) throws IOException {
        dup0(fd1, fd2);
    }

    FileDescriptor duplicateForMapping(FileDescriptor fd) {
        // file descriptor not required for mapping operations; okay
        // to return invalid file descriptor.
        return new FileDescriptor();
    }

    boolean canTransferToDirectly(java.nio.channels.SelectableChannel sc) {
        return true;
    }

    boolean transferToDirectlyNeedsPositionLock() {
        return false;
    }

    int setDirectIO(FileDescriptor fd, String path) {
        int result = -1;
        try {
            result = setDirect0(fd);
        } catch (IOException e) {
            throw new UnsupportedOperationException
                ("Error setting up DirectIO", e);
        }
        return result;
    }

    static int read0(FileDescriptor fd, long address, int len)
            throws IOException {
        return (int) FFIUtils.convertReturnValue(
                LibC.read(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len),
                true);
    }

    static int pread0(FileDescriptor fd, long address, int len,
                        long position) throws IOException {
        return (int) FFIUtils.convertReturnValue(
                LibC.pread(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len, position),
                true);
    }

    static long readv0(FileDescriptor fd, long address, int len)
            throws IOException {
        return FFIUtils.convertReturnValue(
                LibC.readv(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len),
                true);
    }

    static int write0(FileDescriptor fd, long address, int len)
            throws IOException {
        return (int) FFIUtils.convertReturnValue(
                LibC.write(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len),
                false);
    }

    static int pwrite0(FileDescriptor fd, long address, int len,
                         long position) throws IOException {
        return (int) FFIUtils.convertReturnValue(
                LibC.pwrite(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len, position),
                false);
    }

    static long writev0(FileDescriptor fd, long address, int len)
            throws IOException {
        return FFIUtils.convertReturnValue(
                LibC.writev(fdAccess.get(fd),
                        MemoryAddress.ofLong(address),
                        len),
                false);
    }

    private static long handle(long rv, String msg) throws IOException {
        if (rv >= 0) {
            return rv;
        } else {
            int errno = errno();
            if (errno == EINTR) {
                return IOStatus.INTERRUPTED;
            } else {
                throw new IOException(FFIUtils.getErrorMsg(errno, msg));
            }
        }
    }

    static int force0(FileDescriptor fd, boolean metaData)
            throws IOException {
        int result = LibC.fcntl(fdAccess.get(fd), F_FULLFSYNC);
        if (result == -1 && errno() == ENOTSUP) {
            result = LibC.fsync(fdAccess.get(fd));
        }
        return (int) handle(result, "Force failed");
    }

    static long seek0(FileDescriptor fd, long offset)
            throws IOException {
        return handle((offset < 0) ?
                        LibC.lseek(fdAccess.get(fd), 0, SEEK_CUR) :
                        LibC.lseek(fdAccess.get(fd), offset, SEEK_SET),
                "lseek64 failed");
    }

    static int truncate0(FileDescriptor fd, long size)
            throws IOException {
        return (int) handle(LibC.ftruncate(fdAccess.get(fd), size),
                "Truncation failed");
    }

    static long size0(FileDescriptor fd) throws IOException {
        try (NativeScope s = NativeScope.unboundedScope()) {
            stat64 fbuf = stat64.allocate(s::allocate);
            if (LibC.fstat64(fdAccess.get(fd), fbuf) < 0) {
                return handle(-1, "Size failed");
            }
            // FIXME: Linux need to use BLKGETSIZE64 for block device
            /*
#ifdef BLKGETSIZE64
            if (S_ISBLK(fbuf.st_mode)) {
                uint64_t size;
                if (ioctl(fd, BLKGETSIZE64, &size) < 0)
                return handle(env, -1, "Size failed");
                return (jlong)size;
            }
#endif
             */

            return fbuf.st_size$get();
        }
    }

    static int lock0(FileDescriptor fd, boolean blocking, long pos,
                       long size, boolean shared) throws IOException {
        try (NativeScope s = NativeScope.unboundedScope()) {
            flock fl = flock.allocate(s::allocate);
            fl.l_whence$set((short) SEEK_SET);
            fl.l_len$set((size == Long.MAX_VALUE) ? 0 : size);
            fl.l_start$set(pos);
            fl.l_type$set((short) (shared ? F_RDLCK : F_WRLCK));
            int cmd = blocking ? F_SETLKW : F_SETLK;
            int lockResult = LibC.fcntl(fdAccess.get(fd), cmd, fl.address());
            if (lockResult < 0) {
                int errno = errno();
                if ((cmd == F_SETLK) && (errno == EAGAIN || errno == EACCES)) {
                    return NO_LOCK;
                }
                if (errno == EINTR) {
                    return INTERRUPTED;
                }
                throw new IOException(FFIUtils.getErrorMsg(errno, "Lock failed"));
            }
            return 0;
        }
    }

    static void release0(FileDescriptor fd, long pos, long size)
            throws IOException {
        try (NativeScope s = NativeScope.unboundedScope()) {
            flock fl = flock.allocate(s::allocate);
            fl.l_whence$set((short) SEEK_SET);
            fl.l_len$set((size == Long.MAX_VALUE) ? 0 : size);
            fl.l_start$set(pos);
            fl.l_type$set((short) F_UNLCK);
            int lockResult = LibC.fcntl(fdAccess.get(fd), F_SETLK, fl.address());
            if (lockResult < 0) {
                throw new IOException(FFIUtils.getLastErrorMsg("Release failed"));
            }
        }
    }

    static void close0(FileDescriptor fd) throws IOException {
        closeIntFD(fdAccess.get(fd));
    }

    static void preClose0(FileDescriptor fd) throws IOException {
        if (preCloseFD >= 0) {
            if (LibC.dup2(preCloseFD, fdAccess.get(fd)) < 0) {
                throw new IOException(FFIUtils.getLastErrorMsg("dup2 failed"));
            }
        }
    }

    static void dup0(FileDescriptor fd1, FileDescriptor fd2) throws IOException {
        if (LibC.dup2(fdAccess.get(fd1), fdAccess.get(fd2)) < 0) {
            throw new IOException("dup2 failed");
        }
    }

    static void closeIntFD(int fd) throws IOException {
        if (fd != -1) {
            if (LibC.close(fd) < 0) {
                throw new IOException(FFIUtils.getLastErrorMsg("Close failed"));
            }
        }
    }

    static int setDirect0(FileDescriptor fd) throws IOException {
        try (NativeScope s = NativeScope.unboundedScope()) {
            int fdInt = fdAccess.get(fd);
            statvfs file_stat = statvfs.allocate(s::allocate);
            int result = LibC.fcntl(fdInt, F_NOCACHE, 1);
            if (result == -1) {
                throw new IOException(FFIUtils.getLastErrorMsg("DirectIO setup failed"));
            }
            result = LibC.fstatvfs(fdInt, file_stat);
            if (result == -1) {
                throw new IOException(FFIUtils.getLastErrorMsg("DirectIO setup failed"));
            }
            return (int) file_stat.f_frsize$get();
        }
    }

    static int initFFI() {
        MemoryLayout layout = MemoryLayout.ofSequence(2, C_INT);
        VarHandle reader = layout.varHandle(int.class, MemoryLayout.PathElement.sequenceElement());
        try (MemorySegment sp = MemorySegment.allocateNative(layout)) {
            if (LibC.socketpair(AF_UNIX, SOCK_STREAM, 0, sp) < 0) {
                throw new UncheckedIOException(new IOException(
                        FFIUtils.getLastErrorMsg("socketpair failed")));
            }
            int rv = (int) reader.get(sp, 0);
            LibC.close((int) reader.get(sp, 1));
            return rv;
        }
    }

    static void init() {
        preCloseFD = initFFI();
    }

}
