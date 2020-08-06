/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @build JextractApiTestBase
 * @modules jdk.incubator.jbind/jdk.incubator.jbind jdk.incubator.jextract/jdk.internal.jextract.impl
 * @run testng/othervm -Dforeign.restricted=permit -ea TestDependency
 */

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Position;
import jdk.incubator.jextract.Type;
import jdk.incubator.jbind.SymbolDependencyCollector;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestDependency extends JextractApiTestBase {
    private final static Type C_INT = Type.primitive(Type.Primitive.Kind.Int);
    // We need stdint.h for pointer macro, otherwise evaluation failed and Constant declaration is not created
    final static String BUILTIN_INCLUDE = Paths.get(System.getProperty("java.home"), "conf", "jextract").toString();
    Declaration.Scoped platform;

    private String getSysRoot() {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Process xcrun = new ProcessBuilder("xcrun", "--show-sdk-path").start();
            xcrun.waitFor();
            xcrun.getInputStream().transferTo(output);
            String sysroot = output.toString();
            // Trim tailing \n
            sysroot = sysroot.substring(0, sysroot.length() - 1);
            return sysroot;
        } catch (Throwable t) {
            t.printStackTrace();
            return "";
        }
    }

    @BeforeClass
    public void parse() {
        List<String> opts = new ArrayList<>();
        opts.add("-I");
        opts.add(BUILTIN_INCLUDE);
        if (isMacOSX) {
            opts.add("-isysroot");
            opts.add(getSysRoot());
        }

        platform = parse("platform.h", opts.toArray(String[]::new));
    }

    @Test
    public void testGetTimeOfDay() {
        if (isWindows) {
            return;
        }

        Declaration.Function gettimeofday = findDecl(platform, "gettimeofday", Declaration.Function.class);
        Declaration.Scoped timeval = findDecl(platform, "timeval", Declaration.Scoped.class);
        assertEquals(timeval.kind(), Declaration.Scoped.Kind.STRUCT);

        List<Declaration> deps = SymbolDependencyCollector.collect(gettimeofday);
        int idxTimeVal = deps.indexOf(timeval);
        assertTrue(idxTimeVal >= 0);
        int idxGetTimeOfDay = deps.indexOf(gettimeofday);
        assertTrue(idxGetTimeOfDay > idxTimeVal);
    }

    @Test
    public void testSetTimeOfDay() {
        if (isWindows) {
            return;
        }

        Declaration.Function settimeofday = findDecl(platform, "settimeofday", Declaration.Function.class);
        Declaration.Scoped timeval = findDecl(platform, "timeval", Declaration.Scoped.class);
        Declaration.Scoped timezone = findDecl(platform, "timezone", Declaration.Scoped.class);
        assertEquals(timeval.kind(), Declaration.Scoped.Kind.STRUCT);
        assertEquals(timezone.kind(), Declaration.Scoped.Kind.STRUCT);

        List<Declaration> deps = SymbolDependencyCollector.collect(settimeofday);
        System.out.println(Declaration.toplevel(Position.NO_POSITION, deps.toArray(Declaration[]::new)));
        int idxTimeVal = deps.indexOf(timeval);
        assertTrue(idxTimeVal >= 0);
        int idxTimeZone = deps.indexOf(timezone);
        assertTrue(idxTimeZone >= 0);
        int idxSetTimeOfDay = deps.indexOf(settimeofday);
        assertTrue(idxSetTimeOfDay > idxTimeZone);
        assertTrue(idxSetTimeOfDay > idxTimeVal);
    }
}
