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
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle.VarHandleDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.incubator.foreign.Addressable;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.incubator.foreign.ValueLayout;
import jdk.incubator.jextract.Declaration;

/**
 * A helper class to generate header interface class in source form.
 * After aggregating various constituents of a .java source, build
 * method is called to get overall generated source string.
 */
abstract class JavaSourceBuilder {
    // buffer
    protected StringBuffer sb;
    // current line alignment (number of 4-spaces)
    protected int align;

    protected String currentStructName;
    protected GroupLayout currentStructLayout;

    public abstract void addConstant(String name, Class<?> type, Object value);
    public abstract void addPrimitiveGlobal(String javaName, String nativeName, MemoryLayout layout, Class<?> type, int dimensions);
    public abstract void addRecordTypeGlobal(String javaName, String nativeName, MemoryLayout layout, ClassDesc CD_type, int dimensions);
    public abstract void addPrimitiveField(String fieldName, Declaration parent, Class<?> type, int dimensions);
    public abstract void addRecordTypeField(String fieldName, Declaration parent, ClassDesc CD_type, int dimensions);

    protected abstract String describeMethodHandle(String javaName, String nativeName, MethodType mt, FunctionDescriptor fDesc, boolean varargs);
    protected abstract String describeFunction(String javaName, FunctionDescriptor fDesc);
    protected abstract String describeStructLayout(String javaName, GroupLayout layout);

    protected JavaSourceBuilder(int align) {
        super();
        this.align = align;
        this.sb = new StringBuffer();
    }

    JavaSourceBuilder() {
        this(0);
    }

    protected int align() {
        return align;
    }

    final String PUB_CLS_MODS = "public final ";
    final String PUB_MODS = "public static final ";
    final String PRI_MODS = "private static final ";

    protected void addPackagePrefix(String pkgName) {
        assert pkgName.indexOf('/') == -1 : "package name invalid: " + pkgName;
        sb.append("// Generated by jbind\n\n");
        if (!pkgName.isEmpty()) {
            sb.append("package ");
            sb.append(pkgName);
            sb.append(";\n\n");
        }
        addImportSection();
    }

    protected void addImportSection() {
        sb.append("import jdk.incubator.jbind.core.*;\n");
        sb.append("import java.lang.invoke.MethodHandle;\n");
        sb.append("import java.lang.invoke.VarHandle;\n");
        sb.append("import jdk.incubator.foreign.*;\n");
        sb.append("import jdk.incubator.foreign.MemoryLayout.PathElement;\n");
        sb.append("import static jdk.incubator.foreign.CSupport.*;\n\n");
    }

    protected void addImport(String value) {
        sb.append("import " + value + ";\n");
    }

    protected void classBegin(String name) {
        indent();
        sb.append(PUB_CLS_MODS + "class ");
        sb.append(name);
        sb.append(" {\n");
        incrAlign();
    }

    protected void classBegin(boolean isStatic, String name, String superClass, String... superInterfaces) {
        indent();
        sb.append(isStatic ? PUB_MODS : PUB_CLS_MODS);
        sb.append("class ");
        sb.append(name);
        if (superClass != null && !superClass.isEmpty()) {
            sb.append(" extends " + superClass);
        }
        if (superInterfaces != null && superInterfaces.length != 0) {
            sb.append(" implements ");
            sb.append(String.join(", ", superInterfaces));
        }
        sb.append(" {\n");
        incrAlign();
    }

    protected void classEnd() {
        closeBracket();
    }

    protected void closeBracket() {
        decrAlign();
        indent();
        sb.append("}\n");
    }

    protected String build() {
        String res = sb.toString();
        this.sb = null;
        return res.toString();
    }

    protected void indent() {
        for (int i = 0; i < align; i++) {
            sb.append("    ");
        }
    }

    protected void incrAlign() {
        align++;
    }

    protected void decrAlign() {
        align--;
    }

    public void addLineBreak() {
        sb.append("\n");
    }

    public void addLineBreaks(int lines) {
        while (lines > 0) {
            sb.append("\n");
            lines--;
        }
    }

    public void addFunction(Declaration.Function f, MethodType mtype, FunctionDescriptor desc) {
        String javaName = NamingUtils.toSafeName(f.name());
        String nativeName = NamingUtils.getSymbolInLib(f);
        boolean varargs = f.type().varargs();
        String mh = describeMethodHandle(javaName, nativeName, mtype, desc, varargs);

        indent();
        sb.append(PUB_MODS + mtype.returnType().getName() + " " + javaName + "(");
        String delim = "";
        List<String> pExprs = new ArrayList<>();
        for (int i = 0 ; i < f.parameters().size() ; i++) {
            Class<?> pType = mtype.parameterType(i);
            String pName = f.parameters().get(i).name();
            if (pName.isEmpty()) {
                pName = "x" + i;
            } else {
                pName = NamingUtils.toSafeName(pName);
            }
            if (pType == MemoryAddress.class) {
                pType = Addressable.class;
                pExprs.add(pName + ".address()");
            } else {
                pExprs.add(pName);
            }
            sb.append(delim + pType.getName() + " " + pName);
            delim = ", ";
        }
        if (varargs) {
            String lastArg = "x" + f.parameters().size();
            sb.append(", Object... " + lastArg);
            pExprs.add(lastArg);
        }
        sb.append(") {\n");
        incrAlign();
        indent();
        sb.append("try {\n");
        incrAlign();
        indent();
        if (!mtype.returnType().equals(void.class)) {
            sb.append("return (" + mtype.returnType().getName() + ") ");
        }
        sb.append(mh).append(".invokeExact(").append(String.join(", ", pExprs)).append(");\n");
        decrAlign();
        indent();
        sb.append("} catch (Throwable ex) {\n");
        incrAlign();
        indent();
        sb.append("throw new AssertionError(ex);\n");
        closeBracket();
        closeBracket();
    }

    public void addFunctionalInterface(String name, MethodType mtype,  FunctionDescriptor fDesc) {
        incrAlign();
        indent();
        sb.append("public interface " + name + " {\n");
        incrAlign();
        indent();
        sb.append(mtype.returnType().getName() + " apply(");
        String delim = "";
        for (int i = 0 ; i < mtype.parameterCount() ; i++) {
            sb.append(delim + mtype.parameterType(i).getName() + " x" + i);
            delim = ", ";
        }
        sb.append(");\n");
        addFunctionalFactory(name, mtype, fDesc);
        decrAlign();
        indent();
        sb.append("}\n");
        decrAlign();
        indent();
    }

    public void beginRecordType(String name, GroupLayout layout) {
        String clsName = NamingUtils.toSafeName(name);
        classBegin(true, clsName, "Struct<" + clsName + ">");
        currentStructName = clsName;
        currentStructLayout = layout;
        addStructConstructor(clsName);
        addLineBreak();
        addLayoutHelperMethods(clsName, layout);
    }

    public void beginLibraryClass(String name) {
        classBegin(name);
    }

    // Implementation helper methods
    protected void beginGetter(String javaName, String typeName, int dimensions, boolean isGlobal) {
        indent();
        sb.append(isGlobal ? PUB_MODS : PUB_CLS_MODS);
        sb.append(typeName);
        sb.append(" ");
        sb.append(javaName).append("$get(");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("long idx" + i);
        }
        sb.append(") {\n");
        incrAlign();
    }

    protected void beginSetter(String javaName, String typeName, int dimensions, boolean isGlobal) {
        indent();
        sb.append(isGlobal ? PUB_MODS : PUB_CLS_MODS);
        sb.append("void ").append(javaName).append("$set(");
        for (int i = 0; i < dimensions; i++) {
            sb.append("long idx" + i).append(", ");
        }
        sb.append(typeName);
        sb.append(" value) {\n");
        incrAlign();
    }

    private void addFunctionalFactory(String className, MethodType mtype, FunctionDescriptor fDesc) {
        String desc = describeFunction(className, fDesc);
        indent();
        sb.append(PUB_MODS + "MemorySegment allocate(" + className + " fi) {\n");
        incrAlign();
        indent();
        sb.append("return RuntimeHelper.upcallStub(" + className + ".class, fi, " + desc + ", " +
                "\"" + mtype.toMethodDescriptorString() + "\");\n");
        decrAlign();
        indent();
        sb.append("}\n");
    }

    protected void addStructConstructor(String name) {
        indent();
        sb.append("protected " + name + "(MemorySegment ms) { super(ms); }\n");
        indent();
        sb.append(PUB_MODS + name + " at(MemorySegment ms) { return new " + name + "(ms); }\n");
        indent();
        sb.append(PUB_MODS + name + " allocate(LongFunction<MemorySegment> allocator, int count) {\n");
        incrAlign();
        indent();
        sb.append("return new " + name + "(allocator.apply(sizeof() * count));\n");
        decrAlign();
        indent();
        sb.append("}\n");
        indent();
        sb.append(PUB_MODS + name + " allocate(LongFunction<MemorySegment> allocator) { return allocate(allocator, 1); }\n");
        indent();
        sb.append(PUB_CLS_MODS + name + " offset(int count) { return at(segment().asSlice(sizeof() * count)); }\n");
    }

    protected void addLayoutHelperMethods(String elementName, GroupLayout layout) {
        String layoutStmt = describeStructLayout(elementName, layout);
        indent();
        sb.append(PUB_MODS + "long sizeof() { return " + layoutStmt + ".byteSize(); }\n");
        indent();
        sb.append(PUB_MODS + "long offsetof(String fieldName) { return " + layoutStmt + ".byteOffset(MemoryLayout.PathElement.groupElement(fieldName)); }\n");
        indent();
        sb.append("@Override\n");
        indent();
        sb.append(PUB_CLS_MODS + "GroupLayout getLayout() { return " + layoutStmt + "; }\n");
    }

    protected String simpleName(ClassDesc returnType) {
        String name = returnType.displayName();
        int lastNestSymbol = name.lastIndexOf('$');
        return returnType.displayName().substring(lastNestSymbol + 1);
    }

    protected void emitVHGetter(String typeName, String vhStmt, String addrStmt, int dimensions) {
        indent();
        sb.append("return (").append(typeName).append(") ");
        sb.append(vhStmt);
        sb.append(".get(");
        sb.append(addrStmt);
        for (int i = 0; i < dimensions; i++) {
            sb.append(", idx" + i);
        }
        sb.append(");\n");
    }

    protected void emitVHSetter(String vhStmt, String addrStmt, int dimensions) {
        indent();
        sb.append(vhStmt);
        sb.append(".set(");
        sb.append(addrStmt);
        for (int i = 0; i < dimensions; i++) {
            sb.append(", idx" + i);
        }
        sb.append(", value);\n");
    }

    protected void emitCarrierAddr(String addrStmt, String layoutStmt, int dimensions) {
        indent();
        sb.append("MemorySegment addr = ").append(addrStmt).append(";\n");
        if (dimensions > 0) {
            indent();
            sb.append("long offset = ").append(layoutStmt).append(".byteOffset(\n");
            incrAlign();
            for (int i = 0; i < dimensions; i++) {
                if (i != 0) {
                    sb.append(",\n");
                }
                indent();
                sb.append("MemoryLayout.PathElement.sequenceElement(idx" + i).append(")");
            }
            sb.append(");\n");
            decrAlign();
            indent();
            sb.append("addr = addr.asSlice(offset);\n");
        }
    }

    protected void emitCarrierGetter(String typeName, String addrStmt, String layoutStmt, int dimensions) {
        if (dimensions > 0) {
            emitCarrierAddr(addrStmt, layoutStmt, dimensions);
            addrStmt = "addr";
        }
        indent();
        sb.append("return ").append(typeName).append(".at(").append(addrStmt).append(");\n");
    }

    protected void emitCarrierSetter(String typeName, String addrStmt, String layoutStmt, int dimensions) {
        if (dimensions > 0) {
            emitCarrierAddr(addrStmt, layoutStmt, dimensions);
            addrStmt = "addr";
        }
        indent();
        sb.append(typeName).append(".at(").append(addrStmt).append(").asSegment().copyFrom(value.asSegment());\n");
    }

    protected void emitFieldAddr(String fieldName) {
        long offset = currentStructLayout.byteOffset(MemoryLayout.PathElement.groupElement(fieldName));
        indent();
        sb.append(PUB_MODS).append("long ").append(fieldName).append("$OFFSET = ").append(offset).append("L;\n");
        indent();
        sb.append(PUB_CLS_MODS).append("MemorySegment ").append(fieldName).append("$ptr() {\n");
        incrAlign();
        indent();
        sb.append("return segment().asSlice(").append(offset).append("L);\n");
        decrAlign();
        indent();
        sb.append("}\n");
    }
}
