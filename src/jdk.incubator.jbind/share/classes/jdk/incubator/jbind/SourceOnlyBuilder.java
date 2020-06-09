/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.incubator.foreign.CSupport;
import jdk.incubator.foreign.ForeignLinker;
import jdk.incubator.foreign.ValueLayout;
import jdk.incubator.jextract.Declaration;

/**
 * A helper class to generate header interface class in source form.
 * After aggregating various constituents of a .java source, build
 * method is called to get overall generated source string.
 */
class SourceOnlyBuilder extends JavaSourceBuilder {
    private static final String ABI;
    private static final String ABI_CLASS_ATTR;

    static {
        ABI = CSupport.getSystemLinker().name();
        ABI_CLASS_ATTR = switch (ABI) {
            case CSupport.SysV.NAME -> CSupport.SysV.CLASS_ATTRIBUTE_NAME;
            case CSupport.Win64.NAME -> CSupport.Win64.CLASS_ATTRIBUTE_NAME;
            case CSupport.AArch64.NAME -> CSupport.AArch64.CLASS_ATTRIBUTE_NAME;
            default -> throw new UnsupportedOperationException("Unsupported Foreign Linker: " + ABI);
        };
    }

    @Override
    public void addConstant(String name, Class<?> type, Object value) {
        indent();
        if (type == MemoryAddress.class || type == MemorySegment.class) {
            //todo, skip for now (address constants and string constants)
        } else {
            sb.append(PUB_MODS + type.getName() + " " + name);
            sb.append(" = ");
            if (type == float.class) {
                sb.append(value);
                sb.append("f");
            } else if (type == long.class) {
                sb.append(value);
                sb.append("L");
            } else if (type == double.class) {
                Double v = (Double) value;
                if (Double.isFinite(v)) {
                    sb.append(value);
                    sb.append("d");
                } else {
                    sb.append("Double.valueOf(\"");
                    sb.append(v.toString());
                    sb.append("\")");
                }
            } else {
                sb.append("(" + type.getName() + ")");
                sb.append(value + "L");
            }
            sb.append(";\n");
        }
    }

    @Override
    public void addPrimitiveGlobal(String javaName, String nativeName, MemoryLayout layout, Class<?> type, int dimensions) {
        addLayout(javaName, layout);
        addAddress(javaName, nativeName);
        addVarHandle(javaName, type, dimensions);

        String addrStmt = javaName + "$ADDR";
        boolean isAddr = MemoryAddress.class.isAssignableFrom(type);
        String typeName = isAddr ? "MemoryAddress" : type.getName();
        String vhStmt = getVarHandleName(javaName, null);

        // Getter
        beginGetter(javaName, typeName, dimensions, true);
        emitVHGetter(typeName, vhStmt, addrStmt, dimensions);
        closeBracket();
        // Setter
        beginSetter(javaName, typeName, dimensions, true);
        emitVHSetter(vhStmt, addrStmt, dimensions);
        closeBracket();
    }

    @Override
    public void addRecordTypeGlobal(String javaName, String nativeName, MemoryLayout layout, ClassDesc CD_type, int dimensions) {
        addAddress(javaName, nativeName);

        String addrStmt = javaName + "$ADDR";
        String typeName = simpleName(CD_type);
        String layoutStmt = typeName + "$LAYOUT";

        // Getter
        beginGetter(javaName, typeName, dimensions, true);
        emitCarrierGetter(typeName, addrStmt, layoutStmt, dimensions);
        closeBracket();
        // Setter
        beginSetter(javaName, typeName, dimensions, true);
        emitCarrierSetter(typeName, addrStmt, layoutStmt, dimensions);
        closeBracket();
    }

    @Override
    public void addPrimitiveField(String fieldName, Declaration parent, Class<?> type, int dimensions) {
        String javaName = NamingUtils.toSafeName(fieldName);
        String typeName = type.getName();
        String vhStmt = "getFieldHandle(\"" + fieldName + "\", " + typeName + ".class)";
        String addrStmt = "ptr()";

        // Field address
        emitFieldAddr(javaName);
        // Getter
        beginGetter(javaName, typeName, dimensions, false);
        emitVHGetter(typeName, vhStmt, addrStmt, dimensions);
        closeBracket();
        // Setter
        beginSetter(javaName, typeName, dimensions, false);
        emitVHSetter(vhStmt, addrStmt, dimensions);
        closeBracket();
    }

    @Override
    public void addRecordTypeField(String fieldName, Declaration parent, ClassDesc CD_type, int dimensions) {
        String javaName = NamingUtils.toSafeName(fieldName);
        String typeName = simpleName(CD_type);
        String addrStmt = javaName + "$ptr()";
        String layoutStmt = "$LAYOUT.select(MemoryLayout.PathElement.groupElement(\"" + fieldName + "\"))";

        // Field address
        emitFieldAddr(javaName);
        // Getter
        beginGetter(javaName, typeName, dimensions, false);
        emitCarrierGetter(typeName, addrStmt, layoutStmt, dimensions);
        closeBracket();
        // Setter
        beginSetter(javaName, typeName, dimensions, false);
        emitCarrierSetter(typeName, addrStmt, layoutStmt, dimensions);
        closeBracket();
    }

    @Override
    protected String describeMethodHandle(String javaName, String nativeName, MethodType mt, FunctionDescriptor fDesc, boolean varargs) {
        String mhVar = "mh_" + javaName;
        indent();
        sb.append("public static MethodHandle " + mhVar + ";\n");
        indent();
        sb.append(PUB_MODS + "MethodHandle get" + mhVar + "() {\n");
        incrAlign();
        indent();
        sb.append("if (" + mhVar + " == null) {\n");
        incrAlign();
        indent();
        sb.append(mhVar + " = RuntimeHelper.downcallHandle(\n");
        incrAlign();
        indent();
        sb.append("LIBRARIES, \"" + nativeName + "\"");
        sb.append(",\n");
        indent();
        sb.append("\"" + mt.toMethodDescriptorString() + "\",\n");
        indent();
        addFunction(fDesc);
        sb.append(", ");
        // isVariadic
        sb.append(varargs);
        sb.append("\n");
        decrAlign();
        indent();
        sb.append(");\n");
        decrAlign();
        indent();
        sb.append("}\n");
        indent();
        sb.append("return " + mhVar + ";\n");
        decrAlign();
        indent();
        sb.append("}\n");
        return mhVar;
    }

    @Override
    protected String describeFunction(String javaName, FunctionDescriptor fDesc) {
        String varName = javaName + "$DESC";
        incrAlign();
        indent();
        sb.append(PRI_MODS + "FunctionDescriptor " + varName + " = ");
        addFunction(fDesc);
        sb.append(";\n");
        decrAlign();
        indent();
        return varName;
    }

    @Override
    protected String describeStructLayout(String javaName, GroupLayout layout) {
        indent();
        sb.append(PUB_MODS + "GroupLayout $LAYOUT = ");
        addLayout(layout);
        sb.append(";\n");
        return "$LAYOUT";
    }

    private static boolean matchLayout(ValueLayout a, ValueLayout b) {
        if (a == b) return true;
        return (a.bitSize() == b.bitSize() &&
            a.order() == b.order() &&
            a.bitAlignment() == b.bitAlignment() &&
            a.attribute(ABI_CLASS_ATTR).equals(b.attribute(ABI_CLASS_ATTR)));
    }

    static String typeToLayoutName(ValueLayout vl) {
        if (matchLayout(vl, CSupport.C_BOOL)) {
            return "C_BOOL";
        } else if (matchLayout(vl, CSupport.C_CHAR)) {
            return "C_CHAR";
        } else if (matchLayout(vl, CSupport.C_SHORT)) {
            return "C_SHORT";
        } else if (matchLayout(vl, CSupport.C_INT)) {
            return "C_INT";
        } else if (matchLayout(vl, CSupport.C_LONG)) {
            return "C_LONG";
        } else if (matchLayout(vl, CSupport.C_LONGLONG)) {
            return "C_LONGLONG";
        } else if (matchLayout(vl, CSupport.C_FLOAT)) {
            return "C_FLOAT";
        } else if (matchLayout(vl, CSupport.C_DOUBLE)) {
            return "C_DOUBLE";
        } else if (matchLayout(vl, CSupport.C_LONGDOUBLE)) {
            return "C_LONGDOUBLE";
        } else if (matchLayout(vl, CSupport.C_POINTER)) {
            return "C_POINTER";
        } else {
            throw new RuntimeException("should not reach here, problematic layout: " + vl);
        }
    }

    protected void addLibraries(String[] libraryNames, String[] libraryPaths) {
        indent();
        sb.append(PRI_MODS + "LibraryLookup[] LIBRARIES = RuntimeHelper.libraries(");
        sb.append(stringArray(libraryNames) + ", " + stringArray(libraryPaths) + ");\n");
    }

    private String stringArray(String[] elements) {
        return Stream.of(elements)
                .map(n -> "\"" + n + "\"")
                .collect(Collectors.joining(",", "new String[] {", "}"));
    }

    private String getLayoutName(String elementName) {
        return elementName + "$LAYOUT";
    }

    protected void addLayout(String elementName, MemoryLayout layout) {
        indent();
        sb.append(PUB_MODS + "MemoryLayout " + getLayoutName(elementName) + " = ");
        addLayout(layout);
        sb.append(";\n");
    }

    private void addLayout(MemoryLayout l) {
        if (l instanceof ValueLayout) {
            sb.append(typeToLayoutName((ValueLayout) l));
        } else if (l instanceof SequenceLayout) {
            sb.append("MemoryLayout.ofSequence(");
            if (((SequenceLayout) l).elementCount().isPresent()) {
                sb.append(((SequenceLayout) l).elementCount().getAsLong() + ", ");
            }
            addLayout(((SequenceLayout) l).elementLayout());
            sb.append(")");
        } else if (l instanceof GroupLayout) {
            if (l == CSupport.SysV.C_COMPLEX_LONGDOUBLE) {
                sb.append("C_COMPLEX_LONGDOUBLE");
            } else {
                if (((GroupLayout) l).isStruct()) {
                    sb.append("MemoryLayout.ofStruct(\n");
                } else {
                    sb.append("MemoryLayout.ofUnion(\n");
                }
                incrAlign();
                String delim = "";
                for (MemoryLayout e : ((GroupLayout) l).memberLayouts()) {
                    sb.append(delim);
                    indent();
                    addLayout(e);
                    delim = ",\n";
                }
                sb.append("\n");
                decrAlign();
                indent();
                sb.append(")");
            }
        } else {
            //padding
            sb.append("MemoryLayout.ofPaddingBits(" + l.bitSize() + ")");
        }
        if (l.name().isPresent()) {
            sb.append(".withName(\"" +  l.name().get() + "\")");
        }
    }

    protected void addVarHandle(String name, Class<?> type, int dimensions) {
        String ty = type.getName();
        boolean isAddr = ty.contains("MemoryAddress");
        if (isAddr) {
            ty = "long";
        }
        indent();
        sb.append(PUB_MODS + "VarHandle " + getVarHandleName(name, null) + " = \n");
        incrAlign();
        indent();
        if (isAddr) {
            sb.append("MemoryHandles.asAddressVarHandle(");
        }
        sb.append(getLayoutName(name));
        sb.append(".varHandle(" + ty + ".class");
        for (int i = 0; i < dimensions; i++) {
            sb.append(", PathElement.sequenceElement()");
        }
        sb.append(")");
        if (isAddr) {
            sb.append(")");
        }
        sb.append(";\n");
        decrAlign();
    }

    private void addFunction(FunctionDescriptor f) {
        final boolean noArgs = f.argumentLayouts().isEmpty();
        if (f.returnLayout().isPresent()) {
            sb.append("FunctionDescriptor.of(");
            addLayout(f.returnLayout().get());
            if (!noArgs) {
                sb.append(",");
            }
        } else {
            sb.append("FunctionDescriptor.ofVoid(");
        }
        if (!noArgs) {
            sb.append("\n");
            incrAlign();
            String delim = "";
            for (MemoryLayout e : f.argumentLayouts()) {
                sb.append(delim);
                indent();
                addLayout(e);
                delim = ",\n";
            }
            sb.append("\n");
            decrAlign();
            indent();
        }
        sb.append(")");
    }

    protected void addAddress(String name, String symbol) {
        indent();
        sb.append(PUB_MODS + "MemoryAddress " + name + "$ADDR" + " = ");
        addAddressLookup(name, symbol);
        sb.append(";\n");
    }

    private String getVarHandleName(String name, Declaration parent) {
        return "vh_" + ((parent == null) ? name  : (parent.name() + "$" + name));
    }

    protected void addAddressLookup(String name, String symbol) {
        sb.append("RuntimeHelper.lookupGlobalVariable(LIBRARIES, \"" + symbol + "\", "
                + getLayoutName(name) + ")");
    }
}
