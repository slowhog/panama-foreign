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

public class DeclarationMatch implements Declaration.Visitor<Boolean, Declaration> {
    private TypeMatch typeComparator = new TypeMatch();

    public static boolean match(Declaration a, Declaration b) {
        return new DeclarationMatch().equals(a, b);
    }

    public <T extends Declaration> boolean equals(T a, T b) {
        if (! a.name().equals(b.name())) {
            return false;
        }
        return a.accept(this, b);
    }

    @Override
    public Boolean visitDeclaration(Declaration a, Declaration b) {
        return a.pos() == b.pos();
    }

    @Override
    public Boolean visitScoped(Declaration.Scoped a, Declaration b) {
        if (b instanceof Declaration.Scoped) {
            Declaration.Scoped other = (Declaration.Scoped) b;
            if (a.kind() == other.kind()) {
                switch (a.kind()) {
                    case TOPLEVEL:
                        return a.pos().path() == other.pos().path();
                    case STRUCT:
                    case UNION:
                        // TODO: better approach, layout may have different annotations
                        return a.layout() == other.layout();
                    default:
                        return true;
                }
            }
        }
        return false;
    }

    @Override
    public Boolean visitFunction(Declaration.Function a, Declaration b) {
        if (b instanceof Declaration.Function) {
            Declaration.Function other = (Declaration.Function) b;
            return typeComparator.equals(a.type(), other.type());
        }
        return false;
    }

    @Override
    public Boolean visitVariable(Declaration.Variable a, Declaration b) {
        if (b instanceof Declaration.Variable) {
            Declaration.Variable other = (Declaration.Variable) b;
            return typeComparator.equals(a.type(), other.type());
        }
        return false;
    }

    @Override
    public Boolean visitConstant(Declaration.Constant a, Declaration b) {
        if (b instanceof Declaration.Constant) {
            Declaration.Constant other = (Declaration.Constant) b;
            return (typeComparator.equals(a.type(), other.type()) &&
                    a.value() == other.value());
        }
        return false;
    }

    static class TypeMatch implements Type.Visitor<Boolean, Type> {
        public static boolean match(Type a, Type b) {
            return new TypeMatch().equals(a, b);
        }

        private Type getCanonicalType(Type type) {
            if (type instanceof Type.Delegated) {
                type = getCanonicalType(((Type.Delegated) type).type());
            }
            return type;
        }

        public <T extends Type> boolean equals(T a, T b) {
            return a.accept(this, getCanonicalType(b));
        }

        @Override
        public Boolean visitFunction(Type.Function a, Type other) {
            if (other instanceof Type.Function) {
                Type.Function b = (Type.Function) other;
                if (a.varargs() != b.varargs()) {
                    return false;
                }
                if (! equals(a.returnType(), b.returnType())) {
                    return false;
                }
                if (a.argumentTypes().size() != b.argumentTypes().size()) {
                    return false;
                }
                for (int i = 0; i < a.argumentTypes().size(); i++) {
                    if (! equals(a.argumentTypes().get(i), b.argumentTypes().get(i))) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public Boolean visitPrimitive(Type.Primitive t, Type other) {
            if (other instanceof Type.Primitive) {
                Type.Primitive b = (Type.Primitive) other;
                return t.layout().equals(b.layout());
            }
            return false;
        }

        @Override
        public Boolean visitDelegated(Type.Delegated t, Type other) {
            Type a = getCanonicalType(t);
            return equals(a, other);
        }

        @Override
        public Boolean visitDeclared(Type.Declared t, Type other) {
            if (other instanceof  Type.Declared) {
                return DeclarationMatch.match(t.tree(), ((Type.Declared) other).tree());
            }
            return false;
        }

        @Override
        public Boolean visitArray(Type.Array t, Type other) {
            if (other instanceof Type.Array) {
                Type.Array b = (Type.Array) other;
                return (t.elementCount() == b.elementCount() &&
                        equals(t.elementType(), b.elementType()));
            }
            return false;
        }

        public Boolean visitType(Type t, Type p) { return t == p; }
    }
}
