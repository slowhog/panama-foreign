/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jdk.incubator.foreign.FunctionDescriptor;

public class Configurations {
    private final Log log;
    private final HeaderResolver resolver;
    private final List<String> libs;
    private final List<String> libPaths;
    private final String targetPackageName;
    private final List<Path> sources;
    private final String mainClsName;
    private final boolean useCondy;

    Set<FunctionDescriptor> functionalInterfaces = new HashSet<>();

    Configurations(Log log, HeaderResolver resolver, List<String> libs, List<String> libPaths,
            String targetPkgName, List<Path> sources, String mainClsName, boolean useCondy)
    {
        super();
        this.log = log;
        this.resolver = resolver;
        this.libs = Collections.unmodifiableList(libs);
        this.libPaths = Collections.unmodifiableList(libPaths);
        this.targetPackageName = targetPkgName;
        this.sources = sources;
        this.mainClsName = mainClsName;
        this.useCondy = useCondy;
    }

    static class Builder {
        private final List<String> libraryNames = new ArrayList<>();
        private final List<String> libraryPaths = new ArrayList<>();
        private final HeaderResolver resolver;
        private final Log log;
        private boolean useCondy = false;
        private final String targetPkgName;
        List<Path> sources = new ArrayList<>();
        private String mainClsName = null;

        Builder(Log log, String targetPkgName) {
            this.log = log;
            this.targetPkgName = (targetPkgName == null) ? "" : targetPkgName.trim();
            this.resolver = new HeaderResolver(targetPkgName, log);
        }

        void addLibraryName(String name) {
            libraryNames.add(name);
        }

        void addLibraryPath(String path) {
            libraryPaths.add(path);
        }

        void useCondy(boolean useCondy) {
            this.useCondy = useCondy;
        }

        void usePackageForFolder(Path path, String pkgName) {
            resolver.usePackageForFolder(path, pkgName);
        }

        void addSourceFile(Path file) {
            file = file.normalize().toAbsolutePath();
            resolver.usePackageForFolder(file.getParent());
            sources.add(file);
        }

        void setMainClsName(String name) {
            mainClsName = name;
        }

        Configurations build() {
            return new Configurations(log, resolver,
                    Collections.unmodifiableList(libraryNames),
                    Collections.unmodifiableList(libraryPaths),
                    targetPkgName,
                    Collections.unmodifiableList(sources),
                    mainClsName != null ? mainClsName :
                            libraryNames.isEmpty() ? "LibsByJVM" : "Lib" + libraryNames.get(0),
                    useCondy);
        }
    }

    public Log getLog() { return log; }
    public List<String> getLibs() { return libs; }
    public List<String> getLibPaths() { return libPaths; }
    public String targetPackageName() { return targetPackageName; }
    public HeaderResolver.HeaderPath clsForFile(Path file) {
        return resolver.headerFor(file);
    }
    public List<Path> getSources() { return sources; }
    public String getMainClsName() { return mainClsName; }
    public boolean useCondy() { return useCondy; }

    public static Path getBuiltinHeadersDir() {
        return Paths.get(System.getProperty("java.home"), "conf", "jextract");
    }

    public static Path getBuiltinHeaderFile() {
        return getBuiltinHeadersDir().resolve("builtin$.h");
    }
}
