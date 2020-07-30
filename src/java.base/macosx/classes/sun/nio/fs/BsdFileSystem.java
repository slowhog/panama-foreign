/*
 * Copyright (c) 2008, 2012, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.FileStore;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import jdk.incubator.foreign.SequenceLayout;
import jdk.internal.panama.LibC;
import jdk.internal.panama.LibC.statfs;
import sun.nio.FFIUtils;

import static jdk.internal.panama.sys.mount_h.MNT_NOWAIT;
import static jdk.internal.panama.sys.mount_h.MNT_WAIT;

/**
 * Bsd implementation of FileSystem
 */

class BsdFileSystem extends UnixFileSystem {
    BsdFileSystem(UnixFileSystemProvider provider, String dir) {
        super(provider, dir);
    }

    @Override
    public WatchService newWatchService()
        throws IOException
    {
        // use polling implementation until we implement a BSD/kqueue one
        return new PollingWatchService();
    }

    // lazy initialization of the list of supported attribute views
    private static class SupportedFileFileAttributeViewsHolder {
        static final Set<String> supportedFileAttributeViews =
            supportedFileAttributeViews();
        private static Set<String> supportedFileAttributeViews() {
            Set<String> result = new HashSet<String>();
            result.addAll(standardFileAttributeViews());
            return Collections.unmodifiableSet(result);
        }
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return SupportedFileFileAttributeViewsHolder.supportedFileAttributeViews;
    }

    @Override
    void copyNonPosixAttributes(int ofd, int nfd) {
    }

    /**
     * Returns object to iterate over mount entries
     */
    @Override
    Iterable<UnixMountEntry> getMountEntries() {
        ArrayList<UnixMountEntry> entries = new ArrayList<>();
        try {
            int count = 0;
            int numEntries = LibC.getfsstat(MemoryAddress.NULL, 0, MNT_NOWAIT);
            UnixNativeDispatcher.throwUnixExceptionIf(numEntries <= 0);

            while (count != numEntries) {
                try (NativeScope s = NativeScope.unboundedScope()) {
                    MemorySegment buf = s.allocateArray(statfs.$LAYOUT, numEntries);
                    count = numEntries;
                    numEntries = LibC.getfsstat(buf, (int) buf.byteSize(), MNT_WAIT);
                    UnixNativeDispatcher.throwUnixExceptionIf(count <= 0);
                    // It's possible that a new filesystem gets mounted between
                    // the first getfsstat and the second so loop until consistent
                    if (count != numEntries) {
                        continue;
                    }
                    for (int i = 0; i < numEntries; i++) {
                        UnixMountEntry entry = new UnixMountEntry(statfs.at(buf).offset(i));
                        entries.add(entry);
                    }
                    break;
                }
            }
        } catch (UnixException ex) {
            // nothing we can do
        }
        return entries;
    }

    @Override
    FileStore getFileStore(UnixMountEntry entry) throws IOException {
        return new BsdFileStore(this, entry);
    }
}
