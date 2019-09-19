/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.foreign.abi;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.access.JavaLangInvokeAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.foreign.MemoryAddressImpl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static sun.security.action.GetBooleanAction.privilegedGetProperty;

/**
 * This class implements native call invocation through a so called 'universal adapter'. A universal adapter takes
 * an array of longs together with a call 'recipe', which is used to move the arguments in the right places as
 * expected by the system ABI.
 */
public class ProgrammableInvoker {
    private static final boolean DEBUG =
        privilegedGetProperty("jdk.internal.foreign.ProgrammableInvoker.DEBUG");
    private static final boolean OLD_INVOKER =
        privilegedGetProperty("jdk.internal.foreign.ProgrammableInvoker.OLD_INVOKER");

    private static final JavaLangInvokeAccess JLIA = SharedSecrets.getJavaLangInvokeAccess();
    private static final VarHandle VH_LONG = MemoryHandles.varHandle(long.class, ByteOrder.nativeOrder());
    // Unbound MH for the invoke() method
    private static final MethodHandle INVOKE_MH;
    private static final MethodHandle MH_UNBOX_ADDRESS;
    private static final MethodHandle MH_BOX_ADDRESS;
    private static final MethodHandle MH_BASE_ADDRESS;
    private static final MethodHandle MH_INVOKE_SPEC;
    private static final MethodHandle MH_COPY_BUFFER;
    private static final MethodHandle MH_CLOSE_BUFFERS;
    private static final MethodHandle MH_ALLOCATE_BUFFER;

    private static final Map<ABIDescriptor, Long> adapterStubs = new ConcurrentHashMap<>();

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            INVOKE_MH = lookup.findVirtual(ProgrammableInvoker.class, "invoke",
                    MethodType.methodType(Object.class, Object[].class));
            MH_UNBOX_ADDRESS = lookup.findStatic(MemoryAddressImpl.class, "addressof"
                    , MethodType.methodType(long.class, MemoryAddress.class));
            MH_BOX_ADDRESS = lookup.findStatic(MemoryAddress.class, "ofLong",
                    MethodType.methodType(MemoryAddress.class, long.class));
            MH_BASE_ADDRESS = lookup.findVirtual(MemorySegment.class, "baseAddress",
                    MethodType.methodType(MemoryAddress.class));
            MH_INVOKE_SPEC = lookup.findVirtual(ProgrammableInvoker.class, "invokeSpec",
                    MethodType.methodType(Object.class, Object[].class, Binding.Move[].class, Binding.Move.class));
            MH_COPY_BUFFER = lookup.findStatic(BindingInterpreter.class, "copyBuffer",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class, long.class, long.class, List.class));
            MH_CLOSE_BUFFERS = lookup.findStatic(ProgrammableInvoker.class, "closeBuffers",
                    MethodType.methodType(void.class, List.class));
            MH_ALLOCATE_BUFFER = lookup.findStatic(MemorySegment.class, "allocateNative",
                    MethodType.methodType(MemorySegment.class, long.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private final ABIDescriptor abi;
    private final BufferLayout layout;
    private final long stackArgsBytes;

    private final MethodType type;
    private final FunctionDescriptor function;
    private final CallingSequence callingSequence;

    private final long addr;
    private final long stubAddress;

    public ProgrammableInvoker(ABIDescriptor abi, long addr, CallingSequence callingSequence) {
        this.abi = abi;
        this.layout = BufferLayout.of(abi);
        this.stubAddress = adapterStubs.computeIfAbsent(abi, key -> generateAdapter(key, layout));

        this.addr = addr;
        this.callingSequence = callingSequence;
        this.type = callingSequence.methodType();
        this.function = callingSequence.functionDesc();

        this.stackArgsBytes = callingSequence.moveBindings()
                .map(Binding.Move::storage)
                .filter(s -> abi.arch.isStackType(s.type()))
                .count()
                * abi.arch.typeSize(abi.arch.stackType());
    }

    private MethodHandle getFallbackHandle() {
        return INVOKE_MH.bindTo(this).asCollector(Object[].class, type.parameterCount()).asType(type);
    }

    public MethodHandle getBoundMethodHandle() {
        if (OLD_INVOKER) {
            return getFallbackHandle();
        }

        Binding.Move[] retMoves = callingSequence.returnBindings()
            .stream()
            .filter(Binding.Move.class::isInstance)
            .map(Binding.Move.class::cast)
            .toArray(Binding.Move[]::new);

        if (retMoves.length > 1) {
            return getFallbackHandle();
        }

        MethodType intrinsicType = MethodType.methodType(
                callingSequence.returnBindings().stream()
                    .filter(Binding.Move.class::isInstance)
                    .map(Binding.Move.class::cast)
                    .map(Binding.Move::type)
                    .findFirst()
                    .orElse((Class) void.class), // inference, why?
                callingSequence.moveBindings().map(Binding.Move::type).toArray(Class<?>[]::new));

        MethodHandle lowLevelFallback = MethodHandles.insertArguments(MH_INVOKE_SPEC.bindTo(this), 1,
                    callingSequence.moveBindings().toArray(Binding.Move[]::new),
                    retMoves.length == 0 ? null : retMoves[0])
                .asCollector(Object[].class, intrinsicType.parameterCount())
                .asType(intrinsicType);

        MethodHandle intrinsicHandle = JLIA.nativeMethodHandle(
                intrinsicType,
                lowLevelFallback,
                addr,
                abi.toInternal(),
                callingSequence.moveBindings()
                    .map(Binding.Move::storage)
                    .map(VMStorage::toInternal)
                    .toArray(jdk.internal.invoke.VMStorage[]::new),
                Arrays.stream(retMoves)
                    .map(Binding.Move::storage)
                    .map(VMStorage::toInternal)
                    .toArray(jdk.internal.invoke.VMStorage[]::new),
                true /* needs state transition */);

        List<MemorySegment> tempBuffers = new ArrayList<>();
        int copies = 0;
        int insertPos = -1;
        for (int i = 0; i < type.parameterCount(); i++) {
            List<Binding> bindings = callingSequence.argumentBindings(i);
            // We interpret the bindings in reverse since we have to construct a MethodHandle from the bottom up
            for (int j = bindings.size() - 1; j >= 0; j--) {
                Binding binding = bindings.get(j);
                switch (binding.tag()) {
                    case MOVE -> insertPos++; // handled by fallback
                    case DUP -> {
                        intrinsicHandle = mergeArguments(intrinsicHandle, insertPos - 1, insertPos);
                        insertPos--;
                    }
                    case CONVERT_ADDRESS ->
                        intrinsicHandle = MethodHandles.filterArguments(intrinsicHandle, insertPos, MH_UNBOX_ADDRESS);
                    case BASE_ADDRESS ->
                        intrinsicHandle = MethodHandles.filterArguments(intrinsicHandle, insertPos, MH_BASE_ADDRESS);
                    case DEREFERENCE -> {
                        Binding.Dereference deref = (Binding.Dereference) binding;
                        MethodHandle filter = MethodHandles.filterArguments(
                            deref.varHandle()
                            .toMethodHandle(VarHandle.AccessMode.GET)
                            .asType(MethodType.methodType(deref.type(), MemoryAddress.class)), 0, MH_BASE_ADDRESS);
                        intrinsicHandle = MethodHandles.filterArguments(intrinsicHandle, insertPos, filter);
                    }
                    case COPY_BUFFER -> {
                        Binding.Copy copy = (Binding.Copy) binding;
                        MethodHandle filter = MethodHandles.insertArguments(MH_COPY_BUFFER, 1, copy.size(), copy.alignment(), tempBuffers);
                        intrinsicHandle = MethodHandles.filterArguments(intrinsicHandle, insertPos, filter);
                        copies++;
                    }
                    default -> throw new IllegalArgumentException("Illegal tag: " + binding.tag());
                }
            }
        }

        if (type.returnType() != void.class) {
            MethodHandle returnFilter = MethodHandles.identity(type.returnType());
            List<Binding> bindings = callingSequence.returnBindings();
            for (int j = bindings.size() - 1; j >= 0; j--) {
                Binding binding = bindings.get(j);
                switch (binding.tag()) {
                    case MOVE -> { /* handled by fallback */ }
                    case CONVERT_ADDRESS ->
                        returnFilter = MethodHandles.filterArguments(returnFilter, 0, MH_BOX_ADDRESS);
                    case DEREFERENCE -> {
                        Binding.Dereference deref = (Binding.Dereference) binding;
                        MethodHandle setter = deref.varHandle().toMethodHandle(VarHandle.AccessMode.SET);
                        setter = MethodHandles.filterArguments(
                            setter.asType(MethodType.methodType(void.class, MemoryAddress.class, deref.type())),
                            0, MH_BASE_ADDRESS);
                        returnFilter = MethodHandles.collectArguments(returnFilter,
                            returnFilter.type().parameterCount(), setter);
                    }
                    case DUP ->
                        // FIXME assumes shape like: (MS, ..., MS, T) R, is that good enough?
                        returnFilter = mergeArguments(returnFilter, 0, returnFilter.type().parameterCount() - 2);
                    case ALLOC_BUFFER -> {
                        Binding.Allocate alloc = (Binding.Allocate) binding;
                        returnFilter = MethodHandles.collectArguments(returnFilter, 0,
                                MethodHandles.insertArguments(MH_ALLOCATE_BUFFER, 0, alloc.size(), alloc.alignment()));
                    }
                    default ->
                        throw new IllegalArgumentException("Illegal tag: " + binding.tag());
                }
            }

            intrinsicHandle = MethodHandles.filterReturnValue(intrinsicHandle, returnFilter);
        }

        if (copies > 0) {
            MethodHandle closer = MethodHandles.insertArguments(MH_CLOSE_BUFFERS, 0, tempBuffers);
            closer = MethodHandles.collectArguments(
                    intrinsicType.returnType() == void.class
                        ? MethodHandles.empty(MethodType.methodType(void.class, Throwable.class))
                        : MethodHandles.dropArguments(MethodHandles.identity(intrinsicHandle.type().returnType()), 0, Throwable.class),
                    0,
                    closer); // (Throwable, V) -> V
            intrinsicHandle = MethodHandles.tryFinally(intrinsicHandle, closer);
        }

        return intrinsicHandle;
    }

    private static MethodHandle mergeArguments(MethodHandle mh, int sourceIndex, int destIndex) {
        MethodType oldType = mh.type();
        if (oldType.parameterType(sourceIndex) != oldType.parameterType(destIndex)) {
            // TODO meet?
            throw new IllegalArgumentException("Parameter types differ");
        }
        MethodType newType = oldType.dropParameterTypes(destIndex, destIndex + 1);
        int[] reorder = new int[oldType.parameterCount()];
        for (int i = 0, index = 0; i < reorder.length; i++) {
            if (i != destIndex) {
                reorder[i] = index++;
            } else {
                reorder[i] = sourceIndex;
            }
        }
        return MethodHandles.permuteArguments(mh, newType, reorder);
    }

    private static void closeBuffers(List<MemorySegment> buffers) {
        for (MemorySegment ms : buffers) {
            ms.close();
        }
    }

    Object invoke(Object[] args) {
        List<MemorySegment> tempBuffers = new ArrayList<>();
        try (MemorySegment argBuffer = MemorySegment.allocateNative(layout.size, 64)) {
            MemoryAddress argsPtr = argBuffer.baseAddress();
            MemoryAddress stackArgs;
            if (stackArgsBytes > 0) {
                MemorySegment stackArgsSeg = MemorySegment.allocateNative(stackArgsBytes, 8);
                tempBuffers.add(stackArgsSeg);
                stackArgs = stackArgsSeg.baseAddress();
            } else {
                stackArgs = MemoryAddressImpl.NULL;
            }

            VH_LONG.set(argsPtr.addOffset(layout.arguments_next_pc), addr);
            VH_LONG.set(argsPtr.addOffset(layout.stack_args_bytes), stackArgsBytes);
            VH_LONG.set(argsPtr.addOffset(layout.stack_args), MemoryAddressImpl.addressof(stackArgs));

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                jdk.internal.foreign.abi.BindingInterpreter.unbox(arg, callingSequence.argumentBindings(i),
                        s -> {
                            if (abi.arch.isStackType(s.type())) {
                                return stackArgs.addOffset(s.index() * abi.arch.typeSize(abi.arch.stackType()));
                            }
                            return argsPtr.addOffset(layout.argOffset(s));
                        }, tempBuffers);
            }

            if (DEBUG) {
                System.err.println("Buffer state before:");
                layout.dump(abi.arch, argsPtr, System.err);
            }

            invokeNative(stubAddress, MemoryAddressImpl.addressof(argsPtr));

            if (DEBUG) {
                System.err.println("Buffer state after:");
                layout.dump(abi.arch, argsPtr, System.err);
            }

            return function.returnLayout().isEmpty()
                    ? null
                    : jdk.internal.foreign.abi.BindingInterpreter.box(callingSequence.returnBindings(),
                    s -> argsPtr.addOffset(layout.retOffset(s))); // buffers are leaked
        } finally {
            tempBuffers.forEach(MemorySegment::close);
        }
    }

    Object invokeSpec(Object[] args, Binding.Move[] argBindings, Binding.Move returnBinding) {
        MemorySegment stackArgsSeg = null;
        try (MemorySegment argBuffer = MemorySegment.allocateNative(layout.size, 64)) {
            MemoryAddress argsPtr = argBuffer.baseAddress();
            MemoryAddress stackArgs;
            if (stackArgsBytes > 0) {
                stackArgsSeg = MemorySegment.allocateNative(stackArgsBytes, 8);
                stackArgs = stackArgsSeg.baseAddress();
            } else {
                stackArgs = MemoryAddressImpl.NULL;
            }

            VH_LONG.set(argsPtr.addOffset(layout.arguments_next_pc), addr);
            VH_LONG.set(argsPtr.addOffset(layout.stack_args_bytes), stackArgsBytes);
            VH_LONG.set(argsPtr.addOffset(layout.stack_args), MemoryAddressImpl.addressof(stackArgs));

            for (int i = 0; i < argBindings.length; i++) {
                Binding.Move binding = argBindings[i];
                VMStorage storage = binding.storage();
                MemoryAddress ptr = abi.arch.isStackType(storage.type())
                    ? stackArgs.addOffset(storage.index() * abi.arch.typeSize(abi.arch.stackType()))
                    : argsPtr.addOffset(layout.argOffset(storage));
                BindingInterpreter.writeOverSized(ptr, binding.type(), args[i]);
            }

            if (DEBUG) {
                System.err.println("Buffer state before:");
                layout.dump(abi.arch, argsPtr, System.err);
            }

            invokeNative(stubAddress, MemoryAddressImpl.addressof(argsPtr));

            if (DEBUG) {
                System.err.println("Buffer state after:");
                layout.dump(abi.arch, argsPtr, System.err);
            }

            if (returnBinding == null) {
                return null;
            } else {
                VMStorage storage = returnBinding.storage();
                return BindingInterpreter.read(argsPtr.addOffset(layout.retOffset(storage)), returnBinding.type());
            }
        } finally {
            if (stackArgsSeg != null) {
                stackArgsSeg.close();
            }
        }
    }

    //natives

    static native void invokeNative(long adapterStub, long buff);
    static native long generateAdapter(ABIDescriptor abi, BufferLayout layout);

    private static native void registerNatives();
    static {
        registerNatives();
    }
}

