/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package org.openjdk.bench.jdk.incubator.foreign.nio;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import jdk.incubator.foreign.CSupport;
import jdk.incubator.foreign.NativeScope;
import jdk.incubator.foreign.MemoryAddress;
import org.openjdk.bench.jdk.incubator.foreign.nio.support.LibC;
import org.openjdk.bench.jdk.incubator.foreign.nio.support.UnixFileAttributes;
import org.openjdk.bench.jdk.incubator.foreign.nio.support.UnixNativeDispatcher;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 50, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value=3, jvmArgsAppend={"--enable-preview", "--add-modules", "jdk.incubator.foreign,jdk.incubator.jbind.core", "-Dforeign.restricted=permit"})
public class NativeIO {
    @Benchmark
    public void getcwdinfoBuiltinNio() throws IOException {
        long usage = 0;
        Path cwd = Paths.get(".");
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(cwd)) {
            for (Path p: dir) {
                PosixFileAttributes attrs = Files.readAttributes(p, PosixFileAttributes.class);
                usage += attrs.size();
            }
        }
    }

    @Benchmark
    public void getcwdinfoLibC() {
        long usage = 0;
        try (NativeScope scope = NativeScope.unboundedScope()) {
            MemoryAddress pDIR = LibC.opendir(CSupport.toCString(".", scope));
            MemoryAddress pent = LibC.readdir(pDIR);
            MemoryAddress buffer = scope.allocate(LibC.stat64.$LAYOUT);
            while (pent != MemoryAddress.NULL) {
                LibC.stat64(pent.addOffset(LibC.dirent.d_name$OFFSET), buffer);
                UnixFileAttributes attrs = UnixFileAttributes.from(buffer);
                usage += attrs.size();
                pent = LibC.readdir(pDIR);
            }
            LibC.closedir(pDIR);
        }
    }

    @Benchmark
    public void getcwdinfoJNI() {
        long usage = 0;
        long dir = UnixNativeDispatcher.opendirJNI(".");
        String p = UnixNativeDispatcher.readdirJNI(dir);
        while (p != null) {
            UnixFileAttributes attrs = UnixNativeDispatcher.statJNI(p);
            p = UnixNativeDispatcher.readdirJNI(dir);
            usage += attrs.size();
        }
        UnixNativeDispatcher.closedirJNI(dir);
    }

    @Benchmark
    public void getcwdinfoFFI() {
        long usage = 0;
        long dir = UnixNativeDispatcher.opendirFFI(".");
        String p = UnixNativeDispatcher.readdirFFI(dir);
        while (p != null) {
            UnixFileAttributes attrs = UnixNativeDispatcher.statFFI(p);
            p = UnixNativeDispatcher.readdirFFI(dir);
            usage += attrs.size();
        }
        UnixNativeDispatcher.closedirFFI(dir);
    }

    private void getcwdinfo(UnixNativeDispatcher instance) throws IOException {
        long usage = 0;
        try (DirectoryStream<String> dir = instance.newDirectoryStream(".")) {
            for (String p: dir) {
                UnixFileAttributes attrs = instance.readAttributes(p);
                usage += attrs.size();
            }
        }
    }

    @Benchmark
    public void getcwdinfoWrapJNI() throws IOException {
        getcwdinfo(UnixNativeDispatcher.JNI);
    }

    @Benchmark
    public void getcwdinfoWrapFFI() throws IOException {
        getcwdinfo(UnixNativeDispatcher.FFI);
    }
}