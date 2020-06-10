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

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaFileObject;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Type;

public class StaticWrapperSourceFactory extends AbstractCodeFactory implements Declaration.Visitor<Void, Declaration> {
    private final Set<String> constants = new HashSet<>();
    protected final JavaSourceBuilder builder;
    protected final ConstantHelper constantHelper;
    protected final TypeTranslator typeTranslator = new TypeTranslator();
    private final AnonymousRegistry aliases;
    private final ClassDesc CD_class;

    static JavaFileObject[] generate(Declaration.Scoped decl, String clsName, String pkgName, AnonymousRegistry aliases, List<String> libraryNames, List<String> libraryPaths, boolean useCondy) {
        return new StaticWrapperSourceFactory(clsName, pkgName, aliases, libraryNames, libraryPaths, useCondy).generate(decl);
    }

    public StaticWrapperSourceFactory(String clsName, String pkgName, AnonymousRegistry aliases, List<String> libraryNames, List<String> libraryPaths, boolean useCondy) {
        super(clsName, pkgName, libraryNames, libraryPaths);
        this.aliases = aliases;
        String qualName = pkgName.isEmpty() ? clsName : pkgName + "." + clsName;
        this.CD_class = ClassDesc.of(qualName);
        if (useCondy) {
            this.constantHelper = new ConstantHelper(qualName, libraryNames.toArray(new String[0]));
            this.builder = new HybridBuilder(constantHelper);
        } else {
            this.constantHelper = null;
            this.builder = new SourceOnlyBuilder(libraryNames);
        }
    }

    public JavaFileObject[] generate(Declaration.Scoped decl) {
        builder.addPackagePrefix(pkgName);
        builder.addImport("java.util.function.LongFunction");
        builder.addLineBreak();
        builder.beginLibraryClass(clsName);
        //generate all decls
        decl.members().forEach(this::generateDecl);

        builder.classEnd();
        String src = builder.build();

        JavaFileObject[] rv;
        if (constantHelper != null) {
            rv = new JavaFileObject[2];
            rv[1] = constantHelper.build();
        } else {
            rv = new JavaFileObject[1];
        }
        rv[0] = fileFromString(pkgName, clsName, src);
        return rv;
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
        //generate static wrapper for function
        builder.addFunction(funcTree, mtype, descriptor);
        int i = 0;
        for (Declaration.Variable param : funcTree.parameters()) {
            Type.Function f = getAsFunctionPointer(param.type());
            if (f != null) {
                String name = funcTree.name() + "$" + (param.name().isEmpty() ? "x" + i : param.name());
                name = NamingUtils.toSafeName(name);
                //generate functional interface
                MethodType fitype = typeTranslator.getMethodType(f);
                builder.addFunctionalInterface(name, fitype, Type.descriptorFor(f).orElseThrow());
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
    public Void visitTypedef(Declaration.Typedef tree, Declaration parent) {
        Type type = tree.type();
        if (type instanceof Type.Declared) {
            return visitScoped(((Type.Declared) type).tree(), tree);
        } else {
            return null;
        }
    }

    @Override
    public Void visitVariable(Declaration.Variable tree, Declaration parent) {
        String fieldName = tree.name();
        assert !fieldName.isEmpty();

        if (tree.kind() == Declaration.Variable.Kind.BITFIELD) {
            System.err.println("Encounter bitfield: " + tree.toString());
            System.err.println("  Enclosing declaration: " + parent.toString());
        }

        Type type = tree.type();
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

        // Diagnosis, not suppose to happen
        if (clzName.isEmpty()) {
            System.err.println("Anonymous field typename for " + fieldName);
            System.err.println("  Type declaration for the field is " + ((Type.Declared) type).tree().toString());
            // skip for now
            return null;
        } else if (clzName.contains("MemorySegment")) {
            // Any reason we getting this? Array have being reduced to element type
            System.err.println("MemorySegment type expected for " + fieldName);
            System.err.println("  Type declaration for the field is " + ((Type.Declared) type).tree().toString());
        }

        if (parent == null) {
            String nativeName = NamingUtils.getSymbolInLib(tree);
            // global variable
            if (isRecord) {
                builder.addRecordTypeGlobal(fieldName, nativeName, layout, CD_class.nested(clzName), dimensions);
            } else {
                builder.addPrimitiveGlobal(fieldName, nativeName, layout, clazz, dimensions);
            }
        } else {
            if (isRecord) {
                builder.addRecordTypeField(fieldName, parent, CD_class.nested(clzName), dimensions);
            } else {
                builder.addPrimitiveField(fieldName, parent, clazz, dimensions);
            }
        }
        return null;
    }

    @Override
    public Void visitScoped(Declaration.Scoped d, Declaration parent) {
        if (d.layout().isEmpty()) {
            //skip decl-only
            return null;
        }

        String name = aliases.getName(d, parent);
        if (!d.name().isEmpty() || !isRecord(parent)) {
            // only add explicit struct layout if the struct is not to be flattened inside another struct
            switch (d.kind()) {
                case STRUCT:
                case UNION:
                    builder.beginRecordType(name, (GroupLayout) d.layout().get());
                    d.members().forEach(fieldTree -> {
                        builder.addLineBreak();
                        fieldTree.accept(this, d.name().isEmpty() ? parent : d);
                    });
                    builder.closeBracket();
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
