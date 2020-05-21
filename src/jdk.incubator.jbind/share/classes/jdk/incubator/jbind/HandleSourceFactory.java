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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.tools.JavaFileObject;
import jdk.incubator.foreign.FunctionDescriptor;

/*
 * Scan a header file and generate Java source items for entities defined in that header
 * file. Tree visitor visit methods return true/false depending on whether a
 * particular Tree is processed or skipped.
 */
public class HandleSourceFactory extends  AbstractCodeFactory implements Declaration.Visitor<Void, Declaration> {
    private final Set<String> constants = new HashSet<>();
    protected final JavaSourceBuilder builder = new JavaSourceBuilder(null);
    protected final TypeTranslator typeTranslator = new TypeTranslator();
    private final Configurations ctx;

    static JavaFileObject[] generate(Configurations ctx, Declaration.Scoped decl, String clsName, String pkgName, List<String> libraryNames, List<String> libraryPaths) {
        return new HandleSourceFactory(ctx, clsName, pkgName, libraryNames, libraryPaths).generate(decl);
    }

    public HandleSourceFactory(Configurations ctx, String clsName, String pkgName, List<String> libraryNames, List<String> libraryPaths) {
        super(clsName, pkgName, libraryNames, libraryPaths);
        this.ctx = ctx;
    }

    @Override
    public JavaFileObject[] generate(Declaration.Scoped decl) {
        builder.addPackagePrefix(pkgName);
        builder.classBegin(clsName);
        //generate all decls
        decl.members().forEach(this::generateDecl);

        builder.classEnd();
        String src = builder.build();
        return new JavaFileObject[] {
                fileFromString(pkgName, clsName, src),
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
    public Void visitVariable(Declaration.Variable tree, Declaration parent) {
        return null;
    }

    @Override
    public Void visitFunction(Declaration.Function funcTree, Declaration parent) {
        FunctionDescriptor descriptor = Type.descriptorFor(funcTree.type()).orElse(null);
        if (descriptor == null || LayoutUtils.isIncomplete(descriptor)) {
            // FIXME: pass dynamic array by value cannot have static MH as size not known until callsite
            //abort
            return null;
        }
        //MethodType mtype = typeTranslator.getMethodType(funcTree.type());
        //builder.addMethodHandle(funcTree, mtype, descriptor);
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

    @Override
    public Void visitScoped(Declaration.Scoped d, Declaration parent) {
        if (d.kind() == Declaration.Scoped.Kind.ENUM) {
            d.members().forEach(fieldTree -> fieldTree.accept(this, d.name().isEmpty() ? parent : d));
        }
        return null;
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
