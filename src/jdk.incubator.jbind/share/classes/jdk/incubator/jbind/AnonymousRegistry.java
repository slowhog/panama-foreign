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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Position;
import jdk.incubator.jextract.Type;

import static jdk.incubator.jextract.Declaration.Scoped.Kind.*;

public class AnonymousRegistry {
    // Registry for anonymous declaration and the derived name
    private final Map<Declaration, String> nameRegistry = new HashMap<>();

    public AnonymousRegistry() {
        super();
    }

    protected String getName(Declaration d, Supplier<String> givenName) {
        String name = d.name();
        if (name == null || name.isEmpty()) {
            name = givenName.get();
        }
        return name;
    }

    private void registerTypeVar(Declaration d, String name) {
        if (d instanceof Declaration.Typedef) {
            Declaration.Typedef tmp = (Declaration.Typedef) d;
            Type type = tmp.type();
            // We only care direct typedef to anonymous record type
            if (type instanceof Type.Declared) {
                final Declaration decl = ((Type.Declared) type).tree();
                getName(decl, () -> nameRegistry.computeIfAbsent(decl, k -> name));
            }
        }
    }

    private String mustHaveName(Declaration d) {
        return getName(d).orElseThrow(() -> new IllegalStateException(
            "Expected a name for declaration " + d.pos().toString()));
    }

    public String getName(Declaration d, Declaration parent) {
        if (parent == null) {
            return mustHaveName(d);
        }
        String givenName = getName(d, () -> {
            String name = nameRegistry.get(d);
            if (name == null) {
                name = parent.accept(this.namingScheme, d);
                nameRegistry.put(d, name);
            }
            assert (nameRegistry.get(d).equals(name));
            return name;
        });
        registerTypeVar(d, givenName);
        return givenName;
    }

    public Optional<String> getName(Declaration d) {
        return Optional.ofNullable(getName(d, () -> nameRegistry.get(d)));
    }

    private Declaration.Visitor<String, Declaration> namingScheme = new Declaration.Visitor<>() {
        private String getSuffix(Declaration decl) {
            assert (decl.name() == null || decl.name().isEmpty());
            StringBuilder sb = new StringBuilder("_");
            if (decl instanceof Declaration.Scoped) {
                Declaration.Scoped s = (Declaration.Scoped) decl;
                sb.append(switch (s.kind()) {
                    case STRUCT -> "struct";
                    case UNION -> "union";
                    case ENUM -> "enum";
                    case BITFIELDS -> "bits";
                    default -> throw new IllegalArgumentException("Unexpect anomymous scope kind " + s.kind());
                });
            } else if (decl instanceof Declaration.Typedef) {
                Declaration.Typedef fn = (Declaration.Typedef) decl;
                assert (fn.type() instanceof Type.Function);
                sb.append("fn");
            } else if (decl instanceof Declaration.Variable) {
                Declaration.Variable v = (Declaration.Variable) decl;
                sb.append(switch (v.kind()) {
                    case PARAMETER -> "arg";
                    default -> throw new IllegalArgumentException("Unexpect anomymous variable kind " + v.kind());
                });
            } else {
                throw new IllegalArgumentException("Unexpected anonymous declaration " + decl);
            }
            return sb.append("_")
                .append(decl.pos().line())
                .append("_")
                .append(decl.pos().col())
                .toString();
        }

        @Override
        public String visitScoped(Declaration.Scoped parent, Declaration children) {
            return switch (parent.kind()) {
                case TOPLEVEL -> getSuffix(children);
                default -> visitDeclaration(parent, children);
            };
        }

        @Override
        public String visitVariable(Declaration.Variable parent, Declaration children) {
            return switch (parent.kind()) {
                case GLOBAL -> mustHaveName(parent);
                default -> visitDeclaration(parent, children);
            };
        }

        @Override
        public String visitTypedef(Declaration.Typedef parent, Declaration children) {
            return mustHaveName(parent);
        }

        @Override
        public String visitDeclaration(Declaration parent, Declaration children) {
            return mustHaveName(parent) + getSuffix(children);
        }
    };
}