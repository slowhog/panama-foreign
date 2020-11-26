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
import java.util.function.LongFunction;
import java.util.function.Supplier;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.foreign.MemoryAddressImpl;
import jdk.internal.foreign.NativeMemorySegmentImpl;
import jdk.internal.panama.LibC;
import jdk.internal.vm.annotation.ForceInline;
import sun.nio.ch.IOStatus;

import static jdk.incubator.foreign.CLinker.C_CHAR;
import static jdk.incubator.foreign.CLinker.C_INT;
import static jdk.incubator.foreign.CLinker.C_LONG;
import static jdk.incubator.foreign.CLinker.C_POINTER;
import static jdk.internal.foreign.NativeMemorySegmentImpl.EVERYTHING;
import static jdk.internal.panama.LibC.EAGAIN;
import static jdk.internal.panama.LibC.EINTR;
import static jdk.internal.panama.LibC.EWOULDBLOCK;

public final class FFIUtils {
    private static JavaIOFileDescriptorAccess fda = SharedSecrets.getJavaIOFileDescriptorAccess();

    public static MemorySegment copyToNativeBytes(byte[] ar, LongFunction<MemorySegment> allocator) {
        long len = (ar[ar.length - 1] == '\0') ? ar.length : ar.length + 1;
        MemorySegment buf = allocator.apply(len);
        buf.copyFrom(MemorySegment.ofArray(ar));
        MemoryAccess.setByteAtOffset(buf, len - 1, (byte) 0);
        return buf;
    }

    public static boolean isEmptyString(MemoryAddress addr) {
        return (isNull(addr) ||
            MemoryAccess.getByteAtOffset(EVERYTHING, addr.toRawLongValue()) == 0);
    }

    @ForceInline
    public static MemorySegment ofNativeSegment(long addr, long size) {
        return EVERYTHING.asSlice(addr, size);
    }

    @ForceInline
    public static MemorySegment ofNativeSegment(MemoryAddress addr, long size) {
        return EVERYTHING.asSlice(addr.toRawLongValue(), size);
    }

    @ForceInline
    public static MemorySegment ofNativeSegment(MemoryAddress addr, MemoryLayout layout) {
        return EVERYTHING.asSlice(addr.toRawLongValue(), layout.byteSize());
    }

    @ForceInline
    public static boolean isNull(MemoryAddress addr) {
        return addr.equals(MemoryAddress.NULL);
    }

    public static int errno() {
        return MemoryAccess.getInt(ofNativeSegment(LibC.__error(), C_INT.byteSize()));
    }

    public static void setErrno(int value) {
        MemoryAccess.setInt(ofNativeSegment(LibC.__error(), C_INT.byteSize()), value);
    }

    public static byte[] toByteArray(MemorySegment cstr) {
        if (cstr.address().equals(MemoryAddress.NULL)) {
            return null;
        }
        long len = 0;
        while (MemoryAccess.getByteAtOffset(cstr, len) != 0) len++;
        return cstr.asSlice(0L, len).toByteArray();
    }

    public static String toString(MemorySegment cstr) {
        return new String(toByteArray(cstr));
    }

    public static byte[] toByteArray(MemoryAddress addr, long len) {
        return ofNativeSegment(addr, len).toByteArray();
    }

    public static String toString(MemoryAddress cstr) {
        return new String(toByteArray(EVERYTHING.asSlice(cstr)));
    }

    public static String getErrorMsg(int errno, String defaultDetail) {
        try (MemorySegment buf = MemorySegment.allocateNative(
                MemoryLayout.ofSequence(1024, C_CHAR))) {
            int len = LibC.strerror_r(errno, buf, buf.byteSize());
            return (len > 0) ? toString(buf.address()) : defaultDetail;
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
}
