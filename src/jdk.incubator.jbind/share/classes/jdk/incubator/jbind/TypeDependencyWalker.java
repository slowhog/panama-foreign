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

import java.util.function.BiPredicate;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Position;
import jdk.incubator.jextract.Type;

/**
 * This class walk through all types used by a given declaration or type.
 */
public class TypeDependencyWalker {
    // Avoid loop in types references, e.g, a struct has a field points to itself, like linked list.
    private final Set<Declaration> loopDetection = new HashSet<>();
    // Determine if a declaration should be look into. The referencing declaration is passed as
    // parameter. Return true if we should walk this declaration type, false otherwise.
    private final Declaration.Visitor<Boolean, Declaration> accepted;

    private TypeDependencyWalker(Declaration.Visitor<Boolean, Declaration> handler) {
        super();
        this.accepted = handler;
    }

    public static void walkType(Type type, Declaration reference, Declaration.Visitor<Boolean, Declaration> handler) {
        TypeDependencyWalker walker = new TypeDependencyWalker(handler);
        type.accept(walker.typeWalker, reference);
    }

    public static void walkDeclaration(Declaration decl, Declaration.Visitor<Boolean, Declaration> handler) {
        TypeDependencyWalker walker = new TypeDependencyWalker(handler);
        decl.accept(walker.declWalker, Declaration.toplevel(Position.NO_POSITION, decl));
    }

    private final Type.Visitor<Void, Declaration> typeWalker = new Type.Visitor<Void, Declaration>() {
        @Override
        public Void visitDeclared(Type.Declared type, Declaration reference) {
            Declaration.Scoped scope = type.tree();
            scope.accept(declWalker, reference);
            return null;
        }

        @Override
        public Void visitDelegated(Type.Delegated type, Declaration reference) {
            if (type.kind() == Type.Delegated.Kind.TYPEDEF) {
                // inject typedef declaration
                Declaration.Typedef typedef = Declaration.typedef(
                        reference.pos(), type.name().orElseThrow(), type.type());
                return typedef.accept(declWalker, reference);
            } else {
                type.type().accept(typeWalker, reference);
            }
            return null;
        }

        @Override
        public Void visitArray(Type.Array type, Declaration reference) {
            return type.elementType().accept(typeWalker, reference);
        }

        @Override
        public Void visitFunction(Type.Function type, Declaration reference) {
            // This method add function type as a implicit typedef
            Declaration.Typedef fnTypeDef = Declaration.typedef(reference.pos(), "", type);
            if (accepted.visitTypedef(fnTypeDef, reference)) {
                type.returnType().accept(typeWalker, reference);
                for (Type argType: type.argumentTypes()) {
                    argType.accept(typeWalker, reference);
                }
            }
            return null;
        }

        @Override
        public Void visitType(Type type, Declaration reference) {
            return null;
        }
    };

    private Declaration.Visitor<Void, Declaration> declWalker = new Declaration.Visitor<>() {
        @Override
        public Void visitScoped(Declaration.Scoped scope, Declaration parent) {
            if (loopDetection.contains(scope)) {
                return null;
            }
            if (accepted.visitScoped(scope, parent)) {
                loopDetection.add(scope);
                for (Declaration d: scope.members()) {
                    d.accept(declWalker, scope);
                }
            }
            return null;
        }

        @Override
        public Void visitFunction(Declaration.Function fn, Declaration parent) {
            if (accepted.visitFunction(fn, parent)) {
                // This method add function declaration and dependent types
                Type.Function fnType = fn.type();
                fnType.returnType().accept(typeWalker, fn);
                for (Declaration.Variable arg: fn.parameters()) {
                    arg.accept(declWalker, fn);
                }
            }
            return null;
        }

        @Override
        public Void visitConstant(Declaration.Constant constant, Declaration parent) {
            if (accepted.visitConstant(constant, parent)) {
                constant.type().accept(typeWalker, constant);
            }
            return null;
        }

        @Override
        public Void visitVariable(Declaration.Variable variable, Declaration parent) {
            if (accepted.visitVariable(variable, parent)) {
                variable.type().accept(typeWalker, variable);
            }
            return null;
        }

        @Override
        public Void visitTypedef(Declaration.Typedef typedef, Declaration parent) {
            if (accepted.visitTypedef(typedef, parent)) {
                typedef.type().accept(typeWalker, typedef);
            }
            return null;
        }
    };
}
