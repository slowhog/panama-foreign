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

import java.util.regex.Pattern;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.panama.LibMacOS;
import sun.nio.FFIUtils;
import sun.nio.FFIUtils.Scope;

import static jdk.incubator.foreign.MemoryLayouts.SysV.C_SHORT;
import static jdk.internal.macos.cf.CFString_h.kCFStringEncodingUTF16;
import static sun.nio.fs.MacOSXNativeDispatcher.kCFStringNormalizationFormC;
import static sun.nio.fs.MacOSXNativeDispatcher.kCFStringNormalizationFormD;

/**
 * MacOS implementation of FileSystem
 */

class MacOSXFileSystem extends BsdFileSystem {

    MacOSXFileSystem(UnixFileSystemProvider provider, String dir) {
        super(provider, dir);
    }

    // match in unicode canon_eq
    Pattern compilePathMatchPattern(String expr) {
        return Pattern.compile(expr, Pattern.CANON_EQ) ;
    }

    private static char[] normalizePath(char[] path, int form) {
        if (path.length == 0) {
            return null;
        }
        MemoryAddress csref =
                LibMacOS.CFStringCreateMutable(MemoryAddress.NULL, 0);
        if (FFIUtils.isNull(csref)) {
            throw new OutOfMemoryError("native heap");
        }
        try (Scope s = FFIUtils.localScope()) {
            long byteCounts = C_SHORT.byteSize() * path.length;
            MemoryAddress buf = s.allocate(byteCounts);
            MemoryAddress.copy(MemorySegment.ofArray(path).baseAddress(), buf, byteCounts);
            LibMacOS.CFStringAppendCharacters(csref, buf, path.length);
            LibMacOS.CFStringNormalize(csref, form);
            long len = LibMacOS.CFStringGetLength(csref);
            byteCounts = C_SHORT.byteSize() * (len + 1);
            buf = s.allocate(byteCounts);
            LibMacOS.CFStringGetCString(csref, buf, byteCounts, kCFStringEncodingUTF16);
            char[] rv = new char[(int) len];
            // minus the terminating 0
            byteCounts -= C_SHORT.byteSize();
            MemoryAddress.copy(buf, MemorySegment.ofArray(rv).baseAddress(), byteCounts);
            return rv;
        } finally {
            LibMacOS.CFRelease(csref);
        }
    }

    char[] normalizeNativePath(char[] path) {
        for (char c : path) {
            if (c > 0x80)
                return normalizePath(path, kCFStringNormalizationFormD);
        }
        return path;
    }

    String normalizeJavaPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) > 0x80)
                return new String(normalizePath(path.toCharArray(),
                                  kCFStringNormalizationFormC));
        }
        return path;
    }

}
