/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package jdk.incubator.jbind.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SystemABI;
import jdk.internal.foreign.abi.SharedUtils;

import static jdk.incubator.foreign.MemoryLayouts.C_DOUBLE;
import static jdk.incubator.foreign.MemoryLayouts.C_LONG;
import static jdk.incubator.foreign.MemoryLayouts.C_POINTER;

public class VarargsInvoker {

    private static final MethodHandle INVOKE_MH;
    private final MemoryAddress symbol;
    private final MethodType varargs;
    private final FunctionDescriptor function;

    private VarargsInvoker(MemoryAddress symbol, MethodType type, FunctionDescriptor function) {
        // This MemoryAddress might be on a different thread than where lookup happened
        this.symbol = symbol;
        this.varargs = type;
        this.function = function;
    }

    static {
        try {
            INVOKE_MH = MethodHandles.lookup().findVirtual(VarargsInvoker.class, "invoke", MethodType.methodType(Object.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static MethodHandle make(MemoryAddress symbol, MethodType type, FunctionDescriptor function) {
        VarargsInvoker invoker = new VarargsInvoker(symbol, type, function);
        return INVOKE_MH.bindTo(invoker).asCollector(Object[].class, type.parameterCount())
                .asType(type);
    }

    private Object invoke(Object[] args) throws Throwable {
        // one trailing Object[]
        int nNamedArgs = function.argumentLayouts().size();
        assert(args.length == nNamedArgs + 1);
        // The last argument is the array of vararg collector
        Object[] unnamedArgs = (Object[]) args[args.length - 1];

        int argsCount = nNamedArgs + unnamedArgs.length;
        Class<?>[] argTypes = new Class<?>[argsCount];
        MemoryLayout[] argLayouts = new MemoryLayout[nNamedArgs + unnamedArgs.length];

        int pos = 0;
        for (pos = 0; pos < nNamedArgs; pos++) {
            argTypes[pos] = varargs.parameterType(pos);
            argLayouts[pos] = function.argumentLayouts().get(pos);
        }

        assert pos == nNamedArgs;
        for (Object o: unnamedArgs) {
            argTypes[pos] = regulate(o.getClass());
            argLayouts[pos] = variadicLayout(argTypes[pos]);
            pos++;
        }
        assert pos == argsCount;

        MethodType mt = MethodType.methodType(varargs.returnType(), argTypes);
        FunctionDescriptor f = (function.returnLayout().isEmpty()) ?
                FunctionDescriptor.ofVoid(argLayouts) :
                FunctionDescriptor.of(function.returnLayout().get(), argLayouts);
        MethodHandle mh = SharedUtils.getSystemABI().downcallHandle(symbol, mt, f);
        // flatten argument list so that it can be passed to an asSpreader MH
        Object[] allArgs = new Object[nNamedArgs + unnamedArgs.length];
        System.arraycopy(args, 0, allArgs, 0, nNamedArgs);
        System.arraycopy(unnamedArgs, 0, allArgs, nNamedArgs, unnamedArgs.length);

        return mh.asSpreader(Object[].class, argsCount).invoke(allArgs);
    }

    public static Class<?> unboxIfNeeded(Class<?> clazz) {
        if (clazz == Boolean.class) {
            return boolean.class;
        } else if (clazz == Void.class) {
            return void.class;
        } else if (clazz == Byte.class) {
            return byte.class;
        } else if (clazz == Character.class) {
            return char.class;
        } else if (clazz == Short.class) {
            return short.class;
        } else if (clazz == Integer.class) {
            return int.class;
        } else if (clazz == Long.class) {
            return long.class;
        } else if (clazz == Float.class) {
            return float.class;
        } else if (clazz == Double.class) {
            return double.class;
        } else {
            return clazz;
        }
    }

    private Class<?> regulate(Class<?> c) {
        c = unboxIfNeeded(c);
        if (c.isPrimitive()) {
            return c;
        }
        if (MemoryAddress.class.isAssignableFrom(c)) {
            return MemoryAddress.class;
        }
        if (MemorySegment.class.isAssignableFrom(c)) {
            return MemorySegment.class;
        }
        throw new IllegalArgumentException("Invalid type for ABI: " + c.getTypeName());
    }

    private MemoryLayout variadicLayout(Class<?> c) {
        if (c == char.class || c == byte.class || c == short.class || c == int.class || c == long.class) {
            //it is ok to approximate with a machine word here; numerics arguments in a prototype-less
            //function call are always rounded up to a register size anyway.
            return C_LONG;
        } else if (c == float.class || c == double.class) {
            return C_DOUBLE;
        } else if (MemoryAddress.class.isAssignableFrom(c)) {
            return C_POINTER;
        } else {
            throw new IllegalArgumentException("Unhandled variadic argument class: " + c);
        }
    }
}
