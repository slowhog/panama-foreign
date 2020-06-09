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

import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle.VarHandleDesc;
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
import jdk.incubator.foreign.ValueLayout;
import jdk.incubator.jextract.Declaration;

/**
 * A helper class to generate header interface class in source form.
 * After aggregating various constituents of a .java source, build
 * method is called to get overall generated source string.
 */
class HybridBuilder extends JavaSourceBuilder {
    protected ConstantHelper constantHelper;

    HybridBuilder(ConstantHelper constantHelper, int align) {
        super(align);
        this.constantHelper = constantHelper;
    }

    HybridBuilder(ConstantHelper constantHelper) {
        this(constantHelper, 0);
    }

    @Override
    public void addConstant(String name, Class<?> type, Object value) {
        DirectMethodHandleDesc desc = constantHelper.addConstant(name, type, value);
        indent();
        sb.append(PUB_MODS).append(desc.invocationType().returnType().displayName())
          .append(" ").append(name).append (" = ").append(getCallString(desc)).append(";\n");
    }

    @Override
    public void addPrimitiveGlobal(String javaName, String nativeName, MemoryLayout layout, Class<?> type, int dimensions) {
        constantHelper.addLayout(javaName, layout);
        String addrStmt = getCallString(constantHelper.addAddress(javaName, nativeName, layout));
        String vhStmt = getCallString(constantHelper.addVarHandle(javaName, nativeName, layout, type, null));
        String typeName = type.getName();

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
        String layoutStmt = getCallString(constantHelper.addLayout(javaName, layout));
        String typeName = simpleName(CD_type);
        String addrStmt = getCallString(constantHelper.addAddress(javaName, nativeName, layout));
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

        GroupLayout layout = currentStructLayout;
        MemoryLayout fieldLayout = layout.select(MemoryLayout.PathElement.groupElement(fieldName));
        String vhStmt = getCallString(constantHelper.addVarHandle(
            currentStructName + "$" + javaName, fieldName,
            fieldLayout, type, layout));
        String addrStmt = "ptr()";
        String typeName = type.getName();

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
        String layoutStmt = "$LAYOUT().select(MemoryLayout.PathElement.groupElement(\"" + fieldName + "\"))";

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
        return getCallString(constantHelper.addMethodHandle(javaName, nativeName, mt, fDesc, varargs));
    }

    @Override
    protected String describeFunction(String javaName, FunctionDescriptor fDesc) {
        return getCallString(constantHelper.addFunctionDesc(javaName, fDesc));
    }

    @Override
    protected String describeStructLayout(String javaName, GroupLayout layout) {
        return getCallString(constantHelper.addLayout(javaName, layout));
    }

    protected String getCallString(DirectMethodHandleDesc desc) {
        return desc.owner().displayName() + "." + desc.methodName() + "()";
    }
}
