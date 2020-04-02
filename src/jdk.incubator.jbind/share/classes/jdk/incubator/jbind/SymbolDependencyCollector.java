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

import java.util.List;
import java.util.LinkedList;
import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Type;

public class SymbolDependencyCollector implements Declaration.Visitor<List<Declaration>, List<Declaration>> {
    private SymbolDependencyCollector() {}
    final static SymbolDependencyCollector instance = new SymbolDependencyCollector();

    private static class TypeDeclarationCollector implements Type.Visitor<List<Declaration>, List<Declaration>> {
        private TypeDeclarationCollector() {}
        final static TypeDeclarationCollector instance = new TypeDeclarationCollector();

        @Override
        public List<Declaration> visitPrimitive(Type.Primitive type, List<Declaration> base) {
            return base;
        }

        @Override
        public List<Declaration> visitArray(Type.Array t, List<Declaration> base) {
            return t.elementType().accept(this, base);
        }

        @Override
        public List<Declaration> visitFunction(Type.Function t, List<Declaration> base) {
            base = t.returnType().accept(this, base);
            for (Type argType: t.argumentTypes()) {
                base = argType.accept(this, base);
            }
            return base;
        }

        @Override
        public List<Declaration> visitDeclared(Type.Declared t, List<Declaration> base) {
            Declaration d = t.tree();
            if (! base.contains(d)) {
                base.add(0, d);
            }
            return base;
        }

        @Override
        public List<Declaration> visitDelegated(Type.Delegated t, List<Declaration> base) {
            return t.type().accept(this, base);
        }

        public static List<Declaration> collect(Type type, List<Declaration> base) {
            return type.accept(instance, base);
        }
    }

    @Override
    public List<Declaration> visitScoped(Declaration.Scoped scope, List<Declaration> base) {
        for (Declaration d: scope.members()) {
            base = d.accept(this, base);
        }
        return base;
    }

    @Override
    public List<Declaration> visitFunction(Declaration.Function fn, List<Declaration> base) {
        return TypeDeclarationCollector.collect(fn.type(), base);
    }

    @Override
    public List<Declaration> visitConstant(Declaration.Constant constant, List<Declaration> base) {
        return TypeDeclarationCollector.collect(constant.type(), base);
    }

    @Override
    public List<Declaration> visitVariable(Declaration.Variable variable, List<Declaration> base) {
        return TypeDeclarationCollector.collect(variable.type(), base);
    }

    public static List<Declaration> collect(Declaration decl) {
        List<Declaration> rv = new LinkedList<>();
        rv.add(decl);
        return decl.accept(instance, rv);
    }
}
