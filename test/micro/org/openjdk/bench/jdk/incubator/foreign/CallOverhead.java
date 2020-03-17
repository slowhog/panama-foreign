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
package org.openjdk.bench.jdk.incubator.foreign;

import jdk.incubator.foreign.Foreign;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.SystemABI;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

import static jdk.incubator.foreign.MemoryLayouts.C_INT;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
public class CallOverhead {

    static final SystemABI abi = Foreign.getInstance().getSystemABI();
    static final MethodHandle func;
    static final MethodHandle identity;
    static final MethodHandle func_trivial;
    static final MethodHandle identity_trivial;

    static {
        System.loadLibrary("CallOverheadJNI");

        try {
            LibraryLookup ll = LibraryLookup.ofLibrary(MethodHandles.lookup(), "CallOverhead");
            {
                MemoryAddress addr = ll.lookup("func");
                MethodType mt = MethodType.methodType(void.class);
                FunctionDescriptor fd = FunctionDescriptor.ofVoid();
                func = abi.downcallHandle(addr, mt, fd);
                func_trivial = abi.downcallHandle(addr, mt, fd.withAttribute(FunctionDescriptor.IS_TRIVIAL, "true"));
            }
            {
                MemoryAddress addr = ll.lookup("identity");
                MethodType mt = MethodType.methodType(int.class, int.class);
                FunctionDescriptor fd = FunctionDescriptor.of(C_INT, C_INT);
                identity = abi.downcallHandle(addr, mt, fd);
                identity_trivial = abi.downcallHandle(addr, mt, fd.withAttribute(FunctionDescriptor.IS_TRIVIAL, "true"));
            }
        } catch (NoSuchMethodException e) {
            throw new BootstrapMethodError(e);
        }
    }

    static native void blank();
    static native int identity(int x);

    @Benchmark
    public void jni_blank() throws Throwable {
        blank();
    }

    @Benchmark
    public void panama_blank() throws Throwable {
        func.invokeExact();
    }

    @Benchmark
    public void panama_blank_trivial() throws Throwable {
        func_trivial.invokeExact();
    }

    @Benchmark
    public int jni_identity() throws Throwable {
        return identity(10);
    }

    @Benchmark
    public int panama_identity() throws Throwable {
        return (int) identity.invokeExact(10);
    }

    @Benchmark
    public int panama_identity_trivial() throws Throwable {
        return (int) identity_trivial.invokeExact(10);
    }
}
