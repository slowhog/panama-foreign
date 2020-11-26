/*
 * Copyright (c) 2001, 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.net.PortUnreachableException;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.NativeScope;
import jdk.internal.panama.LibC;
import jdk.internal.panama.LibC.iovec;
import jdk.internal.panama.LibC.msghdr;
import sun.nio.FFIUtils;

import static jdk.internal.panama.LibC.IOV_MAX;
import static jdk.internal.panama.LibC.ECONNREFUSED;
import static sun.nio.FFIUtils.errno;

/**
 * Allows different platforms to call different native methods
 * for read and write operations.
 */

class DatagramDispatcher extends NativeDispatcher {

    static {
        IOUtil.load();
    }

    int read(FileDescriptor fd, long address, int len) throws IOException {
        return read0(fd, address, len);
    }

    long readv(FileDescriptor fd, long address, int len) throws IOException {
        return readv0(fd, address, len);
    }

    int write(FileDescriptor fd, long address, int len) throws IOException {
        return write0(fd, address, len);
    }

    long writev(FileDescriptor fd, long address, int len) throws IOException {
        return writev0(fd, address, len);
    }

    void close(FileDescriptor fd) throws IOException {
        FileDispatcherImpl.close0(fd);
    }

    void preClose(FileDescriptor fd) throws IOException {
        FileDispatcherImpl.preClose0(fd);
    }

    void dup(FileDescriptor fd1, FileDescriptor fd2) throws IOException {
        FileDispatcherImpl.dup0(fd1, fd2);
    }

    static int read0(FileDescriptor fd, long address, int len)
        throws IOException {
        long res = LibC.recv(FFIUtils.getFD(fd),
                MemoryAddress.ofLong(address),
                len, 0);
        if (res < 0 && errno() == ECONNREFUSED) {
            throw new PortUnreachableException();
        }
        return (int) FFIUtils.convertReturnValue(res, true);
    }

    static long readv0(FileDescriptor fd, long address, int len)
        throws IOException {
        iovec buf = iovec.at(FFIUtils.ofNativeSegment(address, iovec.sizeof()));
        try (NativeScope s = NativeScope.unboundedScope()) {
            // TODO: Make sure the allocated struct is initialized with 0
            msghdr m = msghdr.allocate(s);
            m.msg_iov$set(buf.address());
            m.msg_iovlen$set(len > IOV_MAX ? IOV_MAX : len);
            long res = LibC.recvmsg(FFIUtils.getFD(fd), m, 0);
            if (res < 0 && errno() == ECONNREFUSED) {
                throw new PortUnreachableException();
            }
            return (int) FFIUtils.convertReturnValue(res, true);
        }
    }

    static int write0(FileDescriptor fd, long address, int len)
        throws IOException {
        long res = LibC.send(FFIUtils.getFD(fd),
                MemoryAddress.ofLong(address),
                len, 0);
        if (res < 0 && errno() == ECONNREFUSED) {
            throw new PortUnreachableException();
        }
        return (int) FFIUtils.convertReturnValue(res, false);
    }

    static long writev0(FileDescriptor fd, long address, int len)
        throws IOException {
        iovec buf = iovec.at(FFIUtils.ofNativeSegment(address, iovec.sizeof()));
        try (NativeScope s = NativeScope.unboundedScope()) {
            // TODO: Make sure the allocated struct is initialized with 0
            msghdr m = msghdr.allocate(s);
            m.msg_iov$set(buf.address());
            m.msg_iovlen$set(len > IOV_MAX ? IOV_MAX : len);
            long res = LibC.sendmsg(FFIUtils.getFD(fd), m, 0);
            if (res < 0 && errno() == ECONNREFUSED) {
                throw new PortUnreachableException();
            }
            return (int) FFIUtils.convertReturnValue(res, false);
        }
    }
}
