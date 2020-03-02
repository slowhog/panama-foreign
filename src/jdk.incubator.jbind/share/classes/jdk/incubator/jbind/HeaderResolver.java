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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class HeaderResolver {

    // The folder path mapping to package name
    private final Map<Path, String> pkgMap = new LinkedHashMap<>();
    // The header file observed
    private final Map<Path, HeaderPath> headerMap = new LinkedHashMap<>();
    private final Log log;
    private final Path builtinHeader;
    private final String defaultPkgName;

    public HeaderResolver(String defaultPkgName, Log log) {
        this.defaultPkgName = defaultPkgName;
        this.log = log;
        usePackageForFolder(Configurations.getBuiltinHeadersDir(),
                defaultPkgName.isEmpty()? "clang_support" : defaultPkgName + ".clang_support");
        this.builtinHeader = Configurations.getBuiltinHeaderFile();
    }

    private static String safeFileName(String filename) {
        int ext = filename.lastIndexOf('.');
        String name = ext != -1 ? filename.substring(0, ext) : filename;
        return NamingUtils.toSafeName(name);
    }

    public static String headerInterfaceName(String filename) {
        return safeFileName(filename) + "_h";
    }

    public static String staticForwarderName(String filename) {
        return safeFileName(filename) + "_lib";
    }

    public void usePackageForFolder(Path folder, String pkg) {
        folder = folder.normalize().toAbsolutePath();
        String existing = pkgMap.putIfAbsent(folder, pkg);
        final String finalFolder = (null == folder) ? "all folders not configured" : folder.toString();
        if (existing == null) {
            log.print(Level.CONFIG, () -> "Package " + pkg + " is selected for " + finalFolder);
        } else {
            String pkgName = pkg.isEmpty() ? "<no package>" : pkg;
            log.print(Level.INFO, () -> "Package " + existing + " had been selected for " + finalFolder + ", request to use " + pkgName + " is ignored.");
        }
    }

    public void usePackageForFolder(Path folder) {
        usePackageForFolder(folder, defaultPkgName);
    }

    // start of header file resolution logic

    static class HeaderPath {
        final String pkg;
        final String headerCls;
        final String forwarderCls;

        HeaderPath(String pkg, String headerCls, String forwarderCls) {
            this.pkg = pkg;
            this.headerCls = headerCls;
            this.forwarderCls = forwarderCls;
        }
    }

    /**
     * Determine package and interface name given a path. If the path is
     * a folder, then only package name is determined. The package name is
     * determined with the longest path matching the setup. If the path is not
     * setup for any package, the default package name is returned.
     *
     * @param origin The source path
     * @return The HeaderPath
     * @see Configurations ::usePackageForFolder(Path, String)
     */
    private HeaderPath resolveHeaderPath(Path origin) {
        // normalize to absolute path
        origin = origin.normalize().toAbsolutePath();
        if (Files.isDirectory(origin)) {
            throw new IllegalStateException("Not an header file: " + origin);
        }
        String filename = origin.getFileName().toString();
        origin = origin.getParent();
        Path path = origin;

        // search the map for a hit with longest path
        while (path != null && !pkgMap.containsKey(path)) {
            path = path.getParent();
        }

        String pkg;
        if (path != null) {
            pkg = pkgMap.get(path);
            if (path.getNameCount() != origin.getNameCount()) {
                String sep = pkg.isEmpty() ? "" : ".";
                for (int i = path.getNameCount() ; i < origin.getNameCount() ; i++) {
                    pkg += sep + NamingUtils.toJavaIdentifier(origin.getName(i).toString());
                    sep = ".";
                }
                usePackageForFolder(origin, pkg);
            }
        } else {
            //infer a package name from path
            List<String> parts = new ArrayList<>();
            for (Path p : origin) {
                parts.add(NamingUtils.toJavaIdentifier(p.toString()));
            }
            pkg = String.join(".", parts);
            usePackageForFolder(origin, pkg);
        }

        return new HeaderPath(pkg, headerInterfaceName(filename), staticForwarderName(filename));
    }

    public HeaderPath headerFor(Path path) {
        if (path == null) {
            path = builtinHeader;
        }

        if (!Files.isRegularFile(path)) {
            log.print(Level.WARNING, "Not a regular file: " + path.toString());
            throw new IllegalArgumentException(path.toString());
        }

        return headerMap.computeIfAbsent(path.normalize().toAbsolutePath(), this::resolveHeaderPath);
    }
}
