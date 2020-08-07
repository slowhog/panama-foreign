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

import java.util.Set;
import java.lang.invoke.TypeDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Type;
import jdk.incubator.jextract.Type.Delegated.Kind;

/**
 * This class collect all declaration needed to fulfill the type used by a given declaration.
 */
public class SymbolDependencyCollector implements Declaration.Visitor<Boolean, Declaration> {
    public static List<Declaration> collect(Declaration decl, AnonymousRegistry registry) {
        SymbolDependencyCollector instance = new SymbolDependencyCollector(registry);
        TypeDependencyWalker.walkDeclaration(decl, instance);
        List<Declaration> decls = new ArrayList<>(instance.dependencies.size() + 1);
        decls.addAll(instance.dependencies);
        decls.add(decl);
        return decls;
    }

    public static List<Declaration> collect(Declaration decl) {
        return collect(decl, new AnonymousRegistry());
    }

    private SymbolDependencyCollector(AnonymousRegistry registry) {
        super();
        this.registry = registry;
        this.dependencies = new LinkedHashSet<>();
    }

    private final AnonymousRegistry registry;
    private final Set<Declaration> dependencies;

    private static boolean isRecord(Declaration declaration) {
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

    @Override
    public Boolean visitScoped(Declaration.Scoped record, Declaration parent) {
        if (dependencies.contains(record)) {
            return false;
        }
        String typeName = registry.getName(record, parent);

        // Not adding record type for anonymous field
        // Named field will have the dependcy via Field declaration
        if (! isRecord(parent)) {
            if (record.name().isEmpty()) {
                // inject implicit typedef
                // Use parent position which leads to the typedef
                dependencies.add(Declaration.typedef(parent.pos(), typeName, Type.declared(record)));
            } else {
                dependencies.add(record);
            }
        }
        return true;
    }

    @Override
    public Boolean visitTypedef(Declaration.Typedef decl, Declaration parent) {
        if (dependencies.contains(decl)) {
            return false;
        }
        if (decl.name().isEmpty()) {
            // Anonymous typedef must be injected by us, give it the new name
            String name = registry.getName(decl, parent);
            dependencies.add(Declaration.typedef(decl.pos(), name, decl.type()));
        } else {
            dependencies.add(decl);
        }
        return true;
    }

    @Override
    public Boolean visitDeclaration(Declaration decl, Declaration parent) {
        if (dependencies.contains(decl)) return false;
        // Ensure we gave any anonymous declaration a name
        registry.getName(decl, parent);
        return true;
    }
}
