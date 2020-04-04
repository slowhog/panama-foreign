/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Type;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaFileObject;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout;

public class StaticWrapperSourceFactory extends AbstractCodeFactory implements Declaration.Visitor<Void, Declaration> {
    private final Set<String> constants = new HashSet<>();
    protected final JavaSourceBuilder builder = new JavaSourceBuilder();
    protected final TypeTranslator typeTranslator = new TypeTranslator();

    static JavaFileObject[] generate(Declaration.Scoped decl, String clsName, String pkgName, List<String> libraryNames, List<String> libraryPaths) {
        return new StaticWrapperSourceFactory(clsName, pkgName, libraryNames, libraryPaths).generate(decl);
    }

    public StaticWrapperSourceFactory(String clsName, String pkgName, List<String> libraryNames, List<String> libraryPaths) {
        super(clsName, pkgName, libraryNames, libraryPaths);
    }

    public JavaFileObject[] generate(Declaration.Scoped decl) {
        builder.addPackagePrefix(pkgName);
        builder.addImport("java.util.function.LongFunction");
        builder.addLineBreak();
        builder.classBegin(clsName);
        builder.addLibraries(libraryNames.toArray(new String[0]),
                libraryPaths != null ? libraryPaths.toArray(new String[0]) : null);
        //generate all decls
        decl.members().forEach(this::generateDecl);

        builder.classEnd();
        String src = builder.build();

        return new JavaFileObject[] {
                fileFromString(pkgName, clsName, src)
        };
    }

    private void generateDecl(Declaration tree) {
        try {
            tree.accept(this, null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public Void visitFunction(Declaration.Function funcTree, Declaration parent) {
        //System.out.println("Deal with function " + funcTree);
        FunctionDescriptor descriptor = Type.descriptorFor(funcTree.type()).orElse(null);
        if (descriptor == null) {
            //abort
            return null;
        }

        MethodType mtype = typeTranslator.getMethodType(funcTree.type());
        builder.addLineBreak();
        builder.addMethodHandle(funcTree, mtype, descriptor);
        //generate static wrapper for function
        builder.addStaticFunctionWrapper(funcTree, mtype);
        int i = 0;
        for (Declaration.Variable param : funcTree.parameters()) {
            Type.Function f = getAsFunctionPointer(param.type());
            if (f != null) {
                String name = funcTree.name() + "$" + (param.name().isEmpty() ? "x" + i : param.name());
                //add descriptor constant
                builder.addDescriptor(name, descriptor);
                //generate functional interface
                MethodType fitype = typeTranslator.getMethodType(f);
                builder.addFunctionalInterface(name, fitype);
                //generate helper
                builder.addFunctionalFactory(name, fitype);
                i++;
            }
        }
        return null;
    }

    Type.Function getAsFunctionPointer(Type type) {
        if (type instanceof Type.Delegated) {
            switch (((Type.Delegated) type).kind()) {
                case POINTER: {
                    Type pointee = ((Type.Delegated) type).type();
                    return (pointee instanceof Type.Function) ?
                        (Type.Function)pointee : null;
                }
                default:
                    return getAsFunctionPointer(((Type.Delegated) type).type());
            }
        } else {
            return null;
        }
    }

    @Override
    public Void visitVariable(Declaration.Variable tree, Declaration parent) {
        String fieldName = tree.name();
        assert !fieldName.isEmpty();
        Type type = tree.type();
        if (tree.kind() == Declaration.Variable.Kind.BITFIELD) {
            System.err.println("Encounter bitfield: " + tree.toString());
            System.err.println("  Enclosing declaration: " + parent.toString());
        }
        MemoryLayout layout = tree.layout().orElse(Type.layoutFor(type).orElse(null));
        if (layout == null) {
            //no layout - abort
            System.err.println("Skip without layout: " + tree.toString());
            return null;
        }

        Map.Entry<Integer, Type> arrayInfo = TypeTranslator.dimension(type);
        type = arrayInfo.getValue();
        int dimensions = arrayInfo.getKey();

        Class<?> clazz = typeTranslator.getJavaType(type);
        boolean isRecord = isRecord(type);
        String clzName = isRecord ? ((Type.Declared) type).tree().name() : clazz.getName();

        if (clzName.isEmpty()) {
            System.err.println("Anonymous field typename for " + fieldName);
            System.err.println("  Type declaration for the field is " + ((Type.Declared) type).tree().toString());
            // skip for now
            return null;
        }

        if (parent == null) {
            // global variable
            builder.addLayout(fieldName, layout);
            builder.addAddress(fieldName, NamingUtils.getSymbolInLib(tree));
        } else {
            builder.addAddress(fieldName, parent);
        }

        //add getter and setters
        if (clsName.contains("MemorySegment")) {
            // FIXME: We cannot construct MS with address
            // skip for now
        } else if (isRecord) {
            builder.addCarrierGetter(fieldName, clzName, parent, dimensions);
            builder.addCarrierSetter(fieldName, clzName, parent, dimensions);
        } else {
            builder.addVarHandle(fieldName, clazz, parent, dimensions);
            builder.addVHGetter(fieldName, clazz, parent, dimensions);
            builder.addVHSetter(fieldName, clazz, parent, dimensions);
        }
        return null;
    }

    @Override
    public Void visitScoped(Declaration.Scoped d, Declaration parent) {
        System.err.println("For Declaration.Scoped " + d.name() +
                (parent == null ? "" : (" in parent " + parent.name())));
        if (d.kind() == Declaration.Scoped.Kind.TYPEDEF) {
            return d.members().get(0).accept(this, d);
        }
        if (d.layout().isEmpty()) {
            //skip decl-only
            return null;
        }
        String name = d.name();
        if (d.name().isEmpty() && parent != null) {
            name = parent.name();
        }

        if (!d.name().isEmpty() || !isRecord(parent)) {
            // only add explicit struct layout if the struct is not to be flattened inside another struct
            // FIXME: anonymous type should have proper name derived, maybe from upstream
            switch (d.kind()) {
                case STRUCT:
                case UNION:
                    String clsName = NamingUtils.toSafeName(d.name());
                    builder.addLineBreak();
                    builder.classBegin(true, clsName, "Struct<" + clsName + ">");
                    builder.addStructConstructor(clsName);
                    builder.addLineBreak();
                    builder.addLayoutMethod(name, (GroupLayout) d.layout().get());
                    d.members().forEach(fieldTree -> {
                        builder.addLineBreak();
                        fieldTree.accept(this, d.name().isEmpty() ? parent : d);
                    });
                    builder.classEnd();
                    break;
            }
        }
        return null;
    }

    @Override
    public Void visitConstant(Declaration.Constant constant, Declaration parent) {
        if (!constants.add(constant.name())) {
            //skip
            return null;
        }

        builder.addConstant(constant.name(), typeTranslator.getJavaType(constant.type()), constant.value());
        return null;
    }

    private boolean isRecord(Type type) {
        if (type == null) {
            return false;
        } else if (!(type instanceof Type.Declared)) {
            return false;
        } else {
            return isRecord(((Type.Declared) type).tree());
        }
    }

    private boolean isRecord(Declaration declaration) {
        if (declaration == null) {
            return false;
        } else if (!(declaration instanceof Declaration.Scoped)) {
            return false;
        } else {
            Declaration.Scoped scope = (Declaration.Scoped)declaration;
            return scope.kind() == Declaration.Scoped.Kind.CLASS ||
                    scope.kind() == Declaration.Scoped.Kind.STRUCT ||
                    scope.kind() == Declaration.Scoped.Kind.UNION;
        }
    }
}
