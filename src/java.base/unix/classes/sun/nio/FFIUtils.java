/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.function.Supplier;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.foreign.MemoryAddressImpl;
import jdk.internal.panama.LibC;
import jdk.internal.vm.annotation.ForceInline;
import sun.nio.ch.IOStatus;

import static jdk.incubator.foreign.CSupport.C_CHAR;
import static jdk.incubator.foreign.CSupport.C_INT;
import static jdk.incubator.foreign.CSupport.C_LONG;
import static jdk.incubator.foreign.CSupport.C_POINTER;
import static jdk.internal.panama.sys.errno_h.EAGAIN;
import static jdk.internal.panama.sys.errno_h.EINTR;
import static jdk.internal.panama.sys.errno_h.EWOULDBLOCK;

public class FFIUtils {
    private static JavaIOFileDescriptorAccess fda = SharedSecrets.getJavaIOFileDescriptorAccess();

    public static class Scope implements AutoCloseable {
        private Scope() {};

        ArrayList<AutoCloseable> used = new ArrayList<>();

        public MemoryAddress copyToNativeBytes(byte[] ar) {
            long len = (ar[ar.length - 1] == '\0') ? ar.length : ar.length + 1;
            MemorySegment buf = MemorySegment.allocateNative(len);
            used.add(buf);
            MemoryAddress ptr = buf.baseAddress();
            buf.copyFrom(MemorySegment.ofArray(ar));
            MemoryHandles.varHandle(byte.class, ByteOrder.nativeOrder()).set(ptr.addOffset(len - 1), (byte) 0);
            return buf.baseAddress();
        }

        public MemoryAddress allocateCString(String str) {
            byte[] data = str.getBytes();
            return copyToNativeBytes(data);
        }

        public MemoryAddress allocate(long bytes) {
            MemorySegment seg = MemorySegment.allocateNative(bytes);
            used.add(seg);
            return seg.baseAddress();
        }

        public MemoryAddress allocateArray(MemoryLayout elementLayout, long count) {
            return allocate(elementLayout.byteSize() * count);
        }

        public MemoryAddress allocate(MemoryLayout layout) {
            return allocate(layout.byteSize());
        }

        public Scope fork() {
            Scope child = new Scope();
            used.add(child);
            return child;
        }

        @Override
        public void close() {
            for (AutoCloseable resource: used) {
                try {
                    resource.close();
                } catch (Exception ex) {
                    // ignore
                }
            }
            used.clear();
        }
    }

    public static Scope localScope() {
        return new Scope();
    }

    public static class CTypeAccess {
        public static VarHandle VH_POINTER = C_POINTER.varHandle(long.class);
        public static VarHandle VH_LONG = C_LONG.varHandle(long.class);
        public static VarHandle VH_UCHAR = C_CHAR.varHandle(byte.class);
        public static VarHandle VH_INT = C_INT.varHandle(int.class);

        public static long readLong(MemoryAddress addr) {
            return (long) VH_LONG.get(addr);
        }
        public static void writeLong(MemoryAddress addr, long value) {
            VH_LONG.set(addr, value);
        }

        public static MemoryAddress readPointer(MemoryAddress addr) {
            return MemoryAddress.ofLong((long) VH_POINTER.get(addr));
        }
        public static void writePointer(MemoryAddress addr, MemoryAddress value) {
            VH_POINTER.set(addr, value.toRawLongValue());
        }

        public static byte readByte(MemoryAddress addr) {
            return (byte) VH_UCHAR.get(addr);
        }
        public static void setByte(MemoryAddress addr, byte value) {
            VH_UCHAR.set(addr, value);
        }

        public static long readInt(MemoryAddress addr) {
            return (long) VH_INT.get(addr);
        }
        public static void writeInt(MemoryAddress addr, int value) {
            VH_INT.set(addr, value);
        }

        public static boolean isEmptyString(MemoryAddress addr) {
            return (isNull(addr) || readByte(resizePointer(addr, 1)) == 0);
        }
    }

    @ForceInline
    public static MemoryAddress resizePointer(MemoryAddress addr, long size) {
        if (addr.segment() == null) {
            return MemoryAddressImpl.ofLongUnchecked(addr.toRawLongValue(), size);
        } else {
            return addr;
        }
    }

    @ForceInline
    public static boolean isNull(MemoryAddress addr) {
        return addr.equals(MemoryAddress.NULL);
    }

    public static int errno() {
        return (int) MemoryHandles.varHandle(int.class, ByteOrder.nativeOrder())
                .get(resizePointer(LibC.__error(), C_INT.byteSize()));
    }
    public static void setErrno(int value) {
        MemoryHandles.varHandle(int.class, ByteOrder.nativeOrder())
                .set(resizePointer(LibC.__error(), C_INT.byteSize()), value);
    }

    public static byte[] toByteArray(MemoryAddress cstr) {
        long len = 0;
        if (cstr.equals(MemoryAddress.NULL)) {
            return null;
        }
        //cstr = resizePointer(cstr, Long.MAX_VALUE);
        VarHandle byteArray = MemoryLayout.ofSequence(C_CHAR)
                .varHandle(byte.class, MemoryLayout.PathElement.sequenceElement());
        while ((byte) byteArray.get(cstr, len) != 0) len++;
        return toByteArray(cstr, len);
    }

    public static byte[] toByteArray(MemoryAddress addr, long len) {
        byte[] ar = new byte[(int) len];
        MemorySegment.ofArray(ar).copyFrom(addr.segment().asSlice(addr.segmentOffset(), len));
        return ar;
    }

    public static String toString(MemoryAddress cstr) {
        return new String(toByteArray(cstr));
    }

    public static String getErrorMsg(int errno, String defaultDetail) {
        try (MemorySegment buf = MemorySegment.allocateNative(
                MemoryLayout.ofSequence(1024, C_CHAR))) {
            int len = LibC.strerror_r(errno, buf.baseAddress(), buf.byteSize());
            return (len > 0) ? toString(buf.baseAddress()) : defaultDetail;
        }
    }

    public static String getLastErrorMsg(String defaultDetail) {
        return getErrorMsg(errno(), defaultDetail);
    }

    public static int getFD(FileDescriptor fdo) {
        return fda.get(fdo);
    }

    public static final <T extends Throwable> void checkErrno(int expected, Supplier<T> ex) throws T {
        if (errno() != expected) {
            throw ex.get();
        }
    }

    public static final long convertReturnValue(long n, boolean reading)
            throws IOException {
        if (n > 0) {
            return n;
        } else if (n == 0) {
            if (reading) {
                return IOStatus.EOF;
            } else {
                return 0;
            }
        } else {
            int errno = errno();
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return IOStatus.UNAVAILABLE;
            } else if (errno == EINTR) {
                return IOStatus.INTERRUPTED;
            } else {
                throw new IOException(getErrorMsg(errno, reading ? "Read failed" : "Write failed"));
            }
        }
    }

    public static MemorySegment asSegment(MemoryAddress addr, long length) {
        return addr.segment().asSlice(addr.segmentOffset(), length);
    }
}
