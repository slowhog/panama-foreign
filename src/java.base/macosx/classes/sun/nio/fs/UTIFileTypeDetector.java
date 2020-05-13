/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.Path;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.panama.LibMacOS;
import sun.nio.FFIUtils;
import sun.nio.FFIUtils.Scope;

import static jdk.incubator.foreign.SystemABI.C_SHORT;

/**
 * File type detector that uses a file extension to look up its MIME type
 * via the Apple Uniform Type Identifier interfaces.
 */
class UTIFileTypeDetector extends AbstractFileTypeDetector {

    UTIFileTypeDetector() {
        super();
    }

    private String toJavaString(MemoryAddress cfStr) {
        if (cfStr == MemoryAddress.NULL) {
            return null;
        }

        long strLength = LibMacOS.CFStringGetLength(cfStr);
        long byteCounts = C_SHORT.byteSize() * strLength;
        MemoryAddress src = LibMacOS.CFStringGetCharactersPtr(cfStr);
        if (src == MemoryAddress.NULL) {
            try (MemorySegment buf = MemorySegment.allocateNative(byteCounts)) {
                src = buf.baseAddress();
                LibMacOS.CFStringGetCharacters(cfStr, LibMacOS.__CFRangeMake(0, strLength), src);
            }
        }
        return new String(FFIUtils.toByteArray(src, byteCounts));
    }

    private String probe0(String fileExtension) {
        try (Scope s = FFIUtils.localScope()) {
            char[] tmp = fileExtension.toCharArray();
            long bytes = C_SHORT.byteSize() * tmp.length;
            MemoryAddress buf = s.allocate(bytes);
            MemoryAddress.copy(MemorySegment.ofArray(tmp).baseAddress(), buf, C_SHORT.byteSize() * tmp.length);
            var ext = LibMacOS.CFStringCreateWithCharacters(MemoryAddress.NULL,
                    buf, bytes);
            if (FFIUtils.isNull(ext)) {
                return null;
            }
            var uti = LibMacOS.UTTypeCreatePreferredIdentifierForTag(
                    LibMacOS.kUTTagClassFilenameExtension$get(), ext, MemoryAddress.NULL);
            LibMacOS.CFRelease(ext);
            if (FFIUtils.isNull(uti)) {
                return null;
            }
            var mimeType = LibMacOS.UTTypeCopyPreferredTagWithClass(uti,
                    LibMacOS.kUTTagClassMIMEType$get());
            LibMacOS.CFRelease(uti);
            if (FFIUtils.isNull(mimeType)) {
                return null;
            }

            String result = toJavaString(mimeType);
            LibMacOS.CFRelease(mimeType);
            return result;
        }
    }

    @Override
    protected String implProbeContentType(Path path) throws IOException {
        Path fn = path.getFileName();
        if (fn == null)
            return null;  // no file name

        String ext = getExtension(fn.toString());
        if (ext.isEmpty())
            return null;  // no extension

        return probe0(ext);
    }

    static {
        jdk.internal.loader.BootLoader.loadLibrary("nio");
    }
}
