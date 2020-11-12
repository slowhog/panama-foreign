/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.incubator.jbind;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.incubator.jbind.core.RuntimeHelper;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.ConstantDynamic;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.constant.ClassDesc;
import java.lang.constant.Constable;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DirectMethodHandleDesc.Kind;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.lang.constant.ConstantDescs.*;
import static java.lang.invoke.MethodHandleInfo.*;
import static java.lang.invoke.MethodType.methodType;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

class ConstantHelper {

    private static final String INTR_OBJECT = Type.getInternalName(Object.class);

    private static final ClassDesc CD_LIBRARIES = desc(LibraryLookup[].class);

    private static final DirectMethodHandleDesc MH_MemoryLayout_varHandle = MethodHandleDesc.ofMethod(
            Kind.INTERFACE_VIRTUAL,
            desc(MemoryLayout.class),
            "varHandle",
            desc(methodType(
                    VarHandle.class,
                    Class.class,
                    MemoryLayout.PathElement[].class))
    );

    private static final DirectMethodHandleDesc MH_MemoryHandles_asAddressVarHandle = MethodHandleDesc.ofMethod(
            Kind.STATIC,
            desc(MemoryHandles.class),
            "asAddressVarHandle",
            desc(methodType(
                    VarHandle.class,
                    VarHandle.class))
    );

    private static final DirectMethodHandleDesc MH_PathElement_groupElement = MethodHandleDesc.ofMethod(
            Kind.INTERFACE_STATIC,
            desc(MemoryLayout.PathElement.class),
            "groupElement",
            desc(methodType(MemoryLayout.PathElement.class, String.class))
    );

    private static final DirectMethodHandleDesc MH_PathElement_sequenceElement = MethodHandleDesc.ofMethod(
            Kind.INTERFACE_STATIC,
            desc(MemoryLayout.PathElement.class),
            "sequenceElement",
            desc(methodType(MemoryLayout.PathElement.class))
    );

    private static final DirectMethodHandleDesc MH_MemoryAddress_ofLong = MethodHandleDesc.ofMethod(
            Kind.INTERFACE_STATIC,
            desc(MemoryAddress.class),
            "ofLong",
            desc(methodType(MemoryAddress.class, long.class))
    );

    private static final DirectMethodHandleDesc MH_MemorySegment_baseAddress = MethodHandleDesc.ofMethod(
            Kind.INTERFACE_VIRTUAL,
            desc(MemorySegment.class),
            "baseAddress",
            desc(methodType(MemoryAddress.class))
    );

    private static final DirectMethodHandleDesc BSM_GET_STATIC_FINAL = ConstantDescs.ofConstantBootstrap(
            CD_ConstantBootstraps,
            "getStaticFinal",
            CD_Object,
            CD_Class
    );

    private static final ConstantDesc TRUE = DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "TRUE",
            ConstantDescs.CD_Boolean, ConstantDescs.CD_Boolean);
    private static final ConstantDesc FALSE = DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "FALSE",
            ConstantDescs.CD_Boolean, ConstantDescs.CD_Boolean);
    private static final ClassDesc CD_PathElelemt = desc(MemoryLayout.PathElement.class);
    private static final ClassDesc CD_MemoryAddress = desc(MemoryAddress.class);
    private static final ClassDesc CD_MemorySegment = desc(MemorySegment.class);
    private static final ConstantDesc SEQUENCE_ELEMENT = DynamicConstantDesc.ofNamed(BSM_INVOKE, "sequenceElement",
            CD_PathElelemt, MH_PathElement_sequenceElement);

    private final DirectMethodHandleDesc MH_downcallHandle;
    private final DirectMethodHandleDesc MH_lookupGlobalVariable;
    private final DirectMethodHandleDesc MH_makeCString;

    private ClassWriter cw;
    private final String internalClassName;
    private final ClassDesc CD_constantsHelper;
    private final ConstantDesc LIBRARIES;

    private final Map<String, DirectMethodHandleDesc> pool = new HashMap<>();

    ConstantHelper(String parentClassName, String[] libraryNames) {
        ClassDesc runtimeHelper = desc(RuntimeHelper.class);
        this.cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String className = parentClassName + "$constants";
        this.CD_constantsHelper = ClassDesc.of(className);
        this.internalClassName = className.replace('.', '/');

        this.MH_downcallHandle = findRuntimeHelperBootstrap(
                runtimeHelper,
                "downcallHandle",
                methodType(
                        MethodHandle.class,
                        LibraryLookup[].class,
                        String.class,
                        String.class,
                        FunctionDescriptor.class,
                        boolean.class)
        );
        this.MH_lookupGlobalVariable = findRuntimeHelperBootstrap(
                runtimeHelper,
                "lookupGlobalVariable",
                methodType(
                        MemoryAddress.class,
                        LibraryLookup[].class,
                        String.class,
                        MemoryLayout.class)
        );
        this.MH_makeCString = findRuntimeHelperBootstrap(
                desc(CLinker.class),
                "toCString",
                methodType(
                        MemorySegment.class,
                        String.class)
        );

        this.LIBRARIES = librariesDesc(findRuntimeHelperBootstrap(
                runtimeHelper,
                "libraries",
                methodType(
                        LibraryLookup[].class,
                        String[].class)
        ), libraryNames);

        cw.visit(V15, ACC_PUBLIC, internalClassName, null, INTR_OBJECT, null);
    }

    private static DirectMethodHandleDesc findRuntimeHelperBootstrap(ClassDesc runtimeHelper, String name, MethodType type) {
        return MethodHandleDesc.ofMethod(
                Kind.STATIC,
                runtimeHelper,
                name,
                desc(type)
        );
    }

    public DirectMethodHandleDesc addLayout(String javaName, MemoryLayout layout) {
        return emitCondyGetter(javaName + "$LAYOUT", layout.getClass(), desc(layout));
    }

    public DirectMethodHandleDesc addVarHandle(String javaName, String nativeName, MemoryLayout layout, Class<?> type, MemoryLayout parentLayout) {
        return emitCondyGetter(javaName + "$VH", VarHandle.class, varHandleDesc(javaName, nativeName, layout, type, parentLayout));
    }

    public DirectMethodHandleDesc addMethodHandle(String javaName, String nativeName, MethodType mtype, FunctionDescriptor desc, boolean varargs) {
        return emitCondyGetter(javaName + "$MH", MethodHandle.class, methodHandleDesc(nativeName, mtype, desc, varargs));
    }

    public DirectMethodHandleDesc addAddress(String javaName, String nativeName, MemoryLayout layout) {
        return emitCondyGetter(javaName + "$ADDR", MemoryAddress.class, globalVarAddressDesc(nativeName, layout));
    }

    public DirectMethodHandleDesc addFunctionDesc(String javaName, FunctionDescriptor fDesc) {
        return emitCondyGetter(javaName + "$DESC", FunctionDescriptor.class, desc(fDesc));
    }

    public DirectMethodHandleDesc addConstant(String name, Class<?> type, Object value) {
        if (type == MemoryAddress.class) {
            if (value instanceof Long) {
                return emitCondyGetter(name, type, addressDesc((Long) value));
            } else if (value instanceof String) {
                return emitCondyGetter(name, type, cStringDesc((String) value));
            } else {
                throw new IllegalStateException("Unhandled constant value type: " + value.getClass());
            }
        } else if (type.isPrimitive()) {
            if (type == int.class || type == byte.class || type == short.class || type == char.class) {
                return emitConIntGetter(name, type, ((Long) value).intValue());
            } else if (type == float.class) {
                return emitConFloatGetter(name, type, ((Double) value).floatValue());
            } else if (type == long.class) {
                return emitConLongGetter(name, type, (Long) value);
            } else if (type == double.class) {
                return emitConDoubleGetter(name, type, (Double) value);
            } else { // boolean and void
                throw new IllegalStateException("Unhandled primitive target type: " + type);
            }
        } else if (type == value.getClass() && value instanceof Constable) {
            // Constable value that requires no conversion
            return emitCondyGetter(name, type, desc((Constable) value));
        } else {
            System.out.println("Warning: Skipping constant generation for: " + name + " of type: " + type.getSimpleName()
                + " with value: " + value + " of type: " + value.getClass());
            return null;
        }
    }

    public JavaFileObject build() {
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        cw = null;
        return jfoFromByteArray(internalClassName, bytes);
    }

    // Utility

    private static JavaFileObject jfoFromByteArray(String name, byte[] bytes) {
        return new SimpleJavaFileObject(URI.create(name + ".class"), JavaFileObject.Kind.CLASS) {
            @Override
            public InputStream openInputStream() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private static String descriptorToInternalName(String s) {
        return s.substring(1, s.length() - 1);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ConstantDesc> T desc(Constable constable) {
        return (T) constable.describeConstable().orElseThrow();
    }

    // ASM helpers

    private static void emitConShort(MethodVisitor mv, short value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(
                switch (value) {
                    case -1 -> ICONST_M1;
                    case 0 -> ICONST_0;
                    case 1 -> ICONST_1;
                    case 2 -> ICONST_2;
                    case 3 -> ICONST_3;
                    case 4 -> ICONST_4;
                    case 5 -> ICONST_5;
                    default -> throw new IllegalStateException("Should not reach here");
                });
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else {
            mv.visitIntInsn(SIPUSH, value);
        }
    }

    private static void emitConInt(MethodVisitor mv, int value) {
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            emitConShort(mv, (short) value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static void emitConLong(MethodVisitor mv, long value) {
        if (value == 0) {
            mv.visitInsn(LCONST_0);
        } else if (value == 1) {
            mv.visitInsn(LCONST_1);
        } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            // saves a constant pool slot
            emitConInt(mv, (int) value);
            mv.visitInsn(I2L);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static void emitConFloat(MethodVisitor mv, float value) {
        if (value == 0.0F) {
            mv.visitInsn(FCONST_0);
        } else if (value == 1.0F) {
            mv.visitInsn(FCONST_1);
        } else if (value == 2.0F) {
            mv.visitInsn(FCONST_2);
        } else if (value == (short) value) {
            emitConShort(mv, (short) value);
            mv.visitInsn(I2F);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static void emitConDouble(MethodVisitor mv, double value) {
        if (value == 0.0D) {
            mv.visitInsn(DCONST_0);
        } else if (value == 1.0D) {
            mv.visitInsn(DCONST_1);
        } else if (value == (short) value) {
            emitConShort(mv, (short) value);
            mv.visitInsn(I2D);
        } else if (value >= Float.MIN_VALUE && value <= Float.MAX_VALUE) {
            // saves a constant pool slot
            mv.visitLdcInsn((float) value);
            mv.visitInsn(F2D);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private DirectMethodHandleDesc emitGetter(String name, Class<?> type, Consumer<MethodVisitor> action) {
        return pool.computeIfAbsent(name, nameKey -> {
            MethodType mt = methodType(type);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, nameKey, mt.descriptorString(), null, null);
            mv.visitCode();
            action.accept(mv);
            emitReturn(mv, type);
            mv.visitMaxs(-1, -1);
            mv.visitEnd();
            return MethodHandleDesc.ofMethod(
                    Kind.STATIC,
                    CD_constantsHelper,
                    nameKey,
                    mt.describeConstable().orElseThrow()
            );
        });
    }

    private DirectMethodHandleDesc emitConDoubleGetter(String name, Class<?> type, double value) {
        return emitGetter(name, type, mv -> emitConDouble(mv, value));
    }

    private DirectMethodHandleDesc emitConLongGetter(String name, Class<?> type, long value) {
        return emitGetter(name, type, mv -> emitConLong(mv, value));
    }

    private DirectMethodHandleDesc emitConFloatGetter(String name, Class<?> type, float value) {
        return emitGetter(name, type, mv -> emitConFloat(mv, value));
    }

    private DirectMethodHandleDesc emitConIntGetter(String name, Class<?> type, int value) {
        return emitGetter(name, type, mv -> emitConInt(mv, value));
    }

    private DirectMethodHandleDesc emitCondyGetter(String name, Class<?> type, ConstantDesc desc) {
        return emitGetter(name, type, mv -> mv.visitLdcInsn(asmConstant(desc)));
    }

    private static void emitReturn(MethodVisitor mv, Class<?> type) {
        if (type == int.class
                || type == short.class
                || type == byte.class
                || type == char.class
                || type == boolean.class) {
            mv.visitInsn(IRETURN);
        } else if (type == long.class) {
            mv.visitInsn(LRETURN);
        } else if (type == float.class) {
            mv.visitInsn(FRETURN);
        } else if (type == double.class) {
            mv.visitInsn(DRETURN);
        } else if (type == void.class) {
            mv.visitInsn(RETURN);
        } else if (Object.class.isAssignableFrom(type)) {
            mv.visitInsn(ARETURN);
        } else {
            throw new IllegalArgumentException("Type not handled: " + type);
        }
    }

    // Condy factories

    private static ConstantDesc librariesDesc(DirectMethodHandleDesc MH_libraries, String[] libraryNames) {
        ConstantDesc[] args = new ConstantDesc[libraryNames.length + 1];
        args[0] = MH_libraries;
        System.arraycopy(libraryNames, 0, args, 1, libraryNames.length);
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "libraries", CD_LIBRARIES, args);
    }

    private static ConstantDesc groupElementDesc(String fieldName) {
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "groupElement_" + fieldName, CD_PathElelemt, MH_PathElement_groupElement, fieldName);
    }

    private static ConstantDesc varHandleDesc(String javaName, String nativeName, MemoryLayout layout, Class<?> type, MemoryLayout parentLayout) {
        boolean isAddr = MemoryAddress.class.isAssignableFrom(type);
        if (isAddr) {
            type = long.class;
        }

        ConstantDesc rv;
        ArrayList<ConstantDesc> args = new ArrayList<>();
        args.add(MH_MemoryLayout_varHandle);

        boolean isField = parentLayout != null;
        if (isField) {
            assert parentLayout instanceof GroupLayout;
            assert ((GroupLayout) parentLayout).select(MemoryLayout.PathElement.groupElement(nativeName)).equals(layout);
            args.add(desc(parentLayout));
        } else {
            args.add(desc(layout));
        }
        args.add(desc(type));
        if (isField) {
            args.add(groupElementDesc(nativeName));
        }
        while (layout instanceof SequenceLayout) {
            args.add(SEQUENCE_ELEMENT);
            layout = ((SequenceLayout) layout).elementLayout();
        }
        rv = DynamicConstantDesc.ofNamed(BSM_INVOKE, "VH_" + javaName, CD_VarHandle, args.toArray(new ConstantDesc[0]));
        if (isAddr) {
            rv = DynamicConstantDesc.ofNamed(BSM_INVOKE, "ASADDRVH", CD_VarHandle, MH_MemoryHandles_asAddressVarHandle, rv);
        }
        return rv;
    }

    private ConstantDesc globalVarAddressDesc(String name, MemoryLayout layout) {
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "ADDR_" + name, CD_MemoryAddress, MH_lookupGlobalVariable, LIBRARIES, name, desc(layout));
    }

    private ConstantDesc addressDesc(long value) {
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "MA_" + value, CD_MemoryAddress, MH_MemoryAddress_ofLong, value);
    }

    private ConstantDesc cStringDesc(String value) {
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "BASEADDRESS", CD_MemoryAddress, MH_MemorySegment_baseAddress,
            DynamicConstantDesc.ofNamed(BSM_INVOKE, "CSTRING", CD_MemorySegment, MH_makeCString, value)
        );
    }

    private ConstantDesc methodHandleDesc(String name, MethodType mtype, FunctionDescriptor funcDesc, boolean varargs) {
        return DynamicConstantDesc.ofNamed(BSM_INVOKE, "MH_" + name, CD_MethodHandle, MH_downcallHandle,
            LIBRARIES,
            name,
            mtype.descriptorString(),
            desc(funcDesc),
            varargs ? TRUE : FALSE);
    }

    // To ASM constant translation

    private static Handle asmHandle(DirectMethodHandleDesc desc) {
        int tag = switch(desc.refKind()) {
            case REF_getField         -> H_GETFIELD;
            case REF_getStatic        -> H_GETSTATIC;
            case REF_putField         -> H_PUTFIELD;
            case REF_putStatic        -> H_PUTSTATIC;
            case REF_invokeVirtual    -> H_INVOKEVIRTUAL;
            case REF_invokeStatic     -> H_INVOKESTATIC;
            case REF_invokeSpecial    -> H_INVOKESPECIAL;
            case REF_newInvokeSpecial -> H_NEWINVOKESPECIAL;
            case REF_invokeInterface  -> H_INVOKEINTERFACE;
            default -> throw new InternalError("Should not reach here");
        };
        return new Handle(tag,
                descriptorToInternalName(desc.owner().descriptorString()),
                desc.methodName(),
                desc.lookupDescriptor(),
                desc.isOwnerInterface());
    }

    private static ConstantDynamic asmCondy(DynamicConstantDesc<?> condy) {
        return new ConstantDynamic(
                condy.constantName(),
                condy.constantType().descriptorString(),
                asmHandle(condy.bootstrapMethod()),
                asmConstantArgs(condy.bootstrapArgs()));
    }

    private static Object[] asmConstantArgs(ConstantDesc[] descs) {
        Object[] objects = new Object[descs.length];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = asmConstant(descs[i]);
        }
        return objects;
    }

    private static Object asmConstant(ConstantDesc desc) {
        if (desc instanceof DynamicConstantDesc<?>) {
            return asmCondy((DynamicConstantDesc<?>) desc);
        } else if (desc instanceof Integer
            || desc instanceof Float
            || desc instanceof Long
            || desc instanceof Double
            || desc instanceof String) {
            return desc;
        } else if (desc instanceof ClassDesc) {
            // Primitives should be caught above
            return Type.getType(((ClassDesc) desc).descriptorString());
        } else if (desc instanceof MethodTypeDesc) {
            return Type.getMethodType(((MethodTypeDesc) desc).descriptorString());
        } else if (desc instanceof DirectMethodHandleDesc) {
            return asmHandle((DirectMethodHandleDesc) desc);
        }
        throw new IllegalArgumentException("ConstantDesc type not handled: " + desc);
    }

}
