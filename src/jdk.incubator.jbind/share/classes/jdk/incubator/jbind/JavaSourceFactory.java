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
import jdk.incubator.jextract.Position;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;

public class JavaSourceFactory implements Declaration.Visitor<Void, Configurations> {
    final Map<Path, List<Declaration>> fileMembers = new HashMap<>();
    // Ensure uniqueness of type declarations, functions, global variables.
    // Exclude constants, as macro or enums can mean differently in it's own scope
    final DeclarationSet uniqFunctions = new DeclarationSet("function prototype");
    final DeclarationSet uniqVariables = new DeclarationSet("variable");
    final DeclarationSet uniqTypes = new DeclarationSet("type declaration");
    final DeclarationSet uniqConstants = new DeclarationSet("constant definition");
    final PatternFilter<Path> headers;
    final PatternFilter<String> symbols;
    final List<Path> sources;

    Configurations ctx;

    public static class Builder {
        private Configurations ctx;
        private PatternFilter<Path> headers = PatternFilter.empty();
        private PatternFilter<String> symbols = PatternFilter.empty();

        Builder(Configurations ctx) {
            this.ctx = ctx;
        }

        public Builder withSymbolFilter(PatternFilter<String> filter) {
            symbols = filter;
            return this;
        }

        public Builder withHeaderFilter(PatternFilter<Path> filter) {
            headers = filter;
            return this;
        }

        public List<JavaFileObject> generate(Declaration.Scoped root) {
            return new JavaSourceFactory(ctx, symbols, headers).generate(root);
        }
    }

    public static Builder of(Configurations ctx) {
        return new Builder(ctx);
    }

    JavaSourceFactory(Configurations cfg, PatternFilter<String> symbols, PatternFilter<Path> headers) {
        this.ctx = cfg;
        this.symbols = symbols;
        this.headers = headers;
        this.sources = (symbols.hasIncludes() || headers.hasIncludes()) ?
                Collections.emptyList() :
                ctx.getSources().stream()
                    .map(Path::toAbsolutePath)
                    .collect(Collectors.toList());
    }

    class DeclarationSet {
        // Must keep encounter order for dependency
        final Map<String, Declaration> decls = new LinkedHashMap<>();
        final String description;

        public DeclarationSet(String description) {
            this.description = description;
        }

        private String position(Position pos) {
            return String.format("%s:%d:%d",
                    pos.path() == null ? "N/A" : pos.path().toString(),
                    pos.line(), pos.col());
        }

        public void add(Declaration d) {
            final String name = d.name();
            final Log log = ctx.getLog();
            if (name.isEmpty()) {
                log.print(Level.WARNING, String.format("Anonymous %s at %s\n",
                        description, position(d.pos())));
            }
            Declaration existing = decls.get(name);

            if (existing == null) {
                decls.put(name, d);
            } else {
                if (existing.equals(d)) {
                    log.print(Level.INFO, String.format("Matching %s: %s", description, name));
                } else {
                    log.print(Level.WARNING, String.format("Override mismatched %s: %s\n\t%s\n\t%s",
                            description, name, position(d.pos()), position(existing.pos())));
                    log.print(Level.WARNING, String.format("  New: ") + d.toString());
                    log.print(Level.WARNING, String.format("  Old: ") + existing.toString());
                    decls.put(name, d);
                }
            }
        }

        public Collection<Declaration> members() {
            return Collections.unmodifiableCollection(decls.values());
        }
    }

    static class FilePosition implements Position {
        private final Path path;

        FilePosition(Path p) {
            this.path = p.normalize().toAbsolutePath();
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public int line() {
            return 0;
        }

        @Override
        public int col() {
            return 0;
        }
    }

    List<JavaFileObject> generateHeader(Path file, Collection<Declaration> members) {
        HeaderResolver.HeaderPath clsInfo = ctx.clsForFile(file);
        Declaration.Scoped root = Declaration.toplevel(
                new FilePosition(file), members.toArray(Declaration[]::new));
        return Arrays.asList(HandleSourceFactory.generate(ctx,
                root, clsInfo.headerCls, clsInfo.pkg,
                ctx.getLibs(), ctx.getLibPaths()));
    }

    public boolean toBeIncluded(Declaration decl) {
        if (symbols.isExcluded(decl.name())) {
            return false;
        }
        Position pos = decl.pos();
        if (headers.isExcluded(pos.path())) {
            return false;
        }
        if (symbols.isIncluded(decl.name())) {
            return true;
        }
        if (headers.isIncluded(pos.path())) {
            return true;
        }
        if (decl instanceof Declaration.Scoped) {
            Declaration.Scoped s = (Declaration.Scoped) decl;
            return (s.kind() == Declaration.Scoped.Kind.ENUM);
        }
        if (decl instanceof Declaration.Constant) {
            return true;
        }
        return sources.contains(pos.path());
    }

    List<JavaFileObject> generateLib(Predicate<Declaration> filter, AnonymousRegistry registry) {
        Declaration.Scoped root = Declaration.toplevel(Position.NO_POSITION, Stream.concat(
                uniqTypes.decls.values().stream(), Stream.concat(
                uniqVariables.decls.values().stream(),
                uniqFunctions.decls.values().stream()))
                .filter(filter)
                .toArray(Declaration[]::new));
        return Arrays.asList(
                StaticWrapperSourceFactory.generate(
                    root, ctx.getMainClsName(), ctx.targetPackageName(),
                    registry, ctx.getLibs(), ctx.getLibPaths(), ctx.useCondy()));
    }

    public List<JavaFileObject> generate(Declaration.Scoped decl) {
        AnonymousRegistry anonymousNames = new AnonymousRegistry();
        decl.members().stream()
                .filter(this::toBeIncluded)
                .flatMap(d -> SymbolDependencyCollector.collect(d, anonymousNames).stream())
                .forEach(d -> d.accept(this, ctx));

        List<Declaration> candidates = new ArrayList<>();
        candidates.addAll(uniqTypes.members());
        // Only explicitly included symbols
        candidates.addAll(uniqConstants.members().stream()
                .filter(d -> symbols.isIncluded(d.name()))
                .collect(Collectors.toList()));
        candidates.addAll(uniqVariables.members());
        candidates.addAll(uniqFunctions.members());
        Declaration.Scoped root = Declaration.toplevel(Position.NO_POSITION,
                candidates.toArray(Declaration[]::new));

        List<JavaFileObject> files = new ArrayList<>();
        /* The indivisual header for constants
        fileMembers.entrySet().stream()
                .filter(e -> headers.filter(e.getKey()))
                .map(e -> generateHeader(e.getKey(), e.getValue()))
                .forEach(files::addAll);
        */
        files.addAll(Arrays.asList(
            StaticWrapperSourceFactory.generate(
                root, ctx.getMainClsName(), ctx.targetPackageName(),
                anonymousNames, ctx.getLibs(), ctx.getLibPaths(), ctx.useCondy())));
        return files;
    }

    private List<JavaFileObject> generateAll(Declaration.Scoped decl) {
        AnonymousRegistry anonymousNames = new AnonymousRegistry();
        decl.members().forEach(m -> anonymousNames.getName(m, decl));
        decl.accept(this, ctx);

        List<JavaFileObject> files = new ArrayList<>();
        fileMembers.entrySet().stream()
                // .peek(e -> System.out.println("Filter " + e.getKey().toString() + "? " + headers.filter(e.getKey())))
                .filter(e -> headers.filter(e.getKey()))
                .map(e -> generateHeader(e.getKey(), e.getValue()))
                .forEach(files::addAll);
        files.addAll(generateLib(d -> toBeIncluded(d), anonymousNames));
        return files;
    }

    @Override
    public Void visitScoped(Declaration.Scoped d, Configurations context) {
        // Non-toplevel
        switch (d.kind()) {
            case TOPLEVEL:
                d.members().forEach(m -> m.accept(this, context));
                // TOPLEVEL we are done
                return null;
            case UNION:
            case STRUCT:
                uniqTypes.add(d);
                break;
            case ENUM:
                // Add all enum constants
                d.members().forEach(m -> m.accept(this, context));
                break;
            default:
        }
        return visitDeclaration(d, context);
    }

    @Override
    public Void visitFunction(Declaration.Function function, Configurations ctx) {
        uniqFunctions.add(function);
        return visitDeclaration(function, ctx);
    }

    @Override
    public Void visitVariable(Declaration.Variable variable, Configurations ctx) {
        if (variable.kind() == Declaration.Variable.Kind.GLOBAL) {
            uniqVariables.add(variable);
        }
        return visitDeclaration(variable, ctx);
    }

    @Override
    public Void visitConstant(Declaration.Constant constant, Configurations ctx) {
        uniqConstants.add(constant);
        return visitDeclaration(constant, ctx);
    }

    @Override
    public Void visitDeclaration(Declaration d, Configurations context) {
        if (d.pos().path() != null) {
            Path file = d.pos().path().normalize().toAbsolutePath();
            List<Declaration> members = fileMembers.computeIfAbsent(file, p -> new ArrayList<>());
            members.add(d);
        }
        return null;
    }
}
