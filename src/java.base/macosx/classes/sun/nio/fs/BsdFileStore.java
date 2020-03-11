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

package sun.nio.fs;

import java.io.IOException;
import java.util.Arrays;
import jdk.incubator.foreign.MemoryAddress;
import jdk.internal.panama.LibC.statfs;
import jdk.internal.panama.LibC;
import sun.nio.FFIUtils;
import sun.nio.FFIUtils.Scope;

/**
 * Bsd implementation of FileStore
 */

class BsdFileStore
    extends UnixFileStore
{
    BsdFileStore(UnixPath file) throws IOException {
        super(file);
    }

    BsdFileStore(UnixFileSystem fs, UnixMountEntry entry) throws IOException {
        super(fs, entry);
    }

    private byte[] getmntonname(UnixPath path) throws UnixException {
        try (Scope s = FFIUtils.localScope()) {
            MemoryAddress cPath = UnixNativeDispatcher.copyToNativeBytes(path, s);
            statfs buf = statfs.allocate(s::allocate);
            UnixNativeDispatcher.throwUnixExceptionIf(
                    0 != LibC.statfs(cPath, buf.ptr()));
            return FFIUtils.toByteArray(buf.f_mntonname$ptr());
        }
    }

    /**
     * Finds, and returns, the mount entry for the file system where the file
     * resides.
     */
    @Override
    UnixMountEntry findMountEntry() throws IOException {
        UnixFileSystem fs = file().getFileSystem();

        // step 1: get realpath
        UnixPath path = null;
        try {
            byte[] rp = UnixNativeDispatcher.realpath(file());
            path = new UnixPath(fs, rp);
        } catch (UnixException x) {
            x.rethrowAsIOException(file());
        }

        // step 2: find mount point
        byte[] dir = null;
        try {
            dir = getmntonname(path);
        } catch (UnixException x) {
            x.rethrowAsIOException(path);
        }

        // step 3: lookup mounted file systems
        for (UnixMountEntry entry: fs.getMountEntries()) {
            if (Arrays.equals(dir, entry.dir()))
                return entry;
        }

        throw new IOException("Mount point not found in fstab");
    }
}
