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

import javax.lang.model.SourceVersion;
import jdk.incubator.jextract.Declaration;

// TODO: Do we want to share jextract.impl.Utils instead of repeat ourselves?
public class NamingUtils {
    private static final boolean isMacOS = System.getProperty("os.name", "").contains("OS X");

    public static String toJavaIdentifier(String str) {
        final int size = str.length();
        StringBuilder sb = new StringBuilder(size);
        if (! Character.isJavaIdentifierStart(str.charAt(0))) {
            sb.append('_');
        }
        for (int i = 0; i < size; i++) {
            char ch = str.charAt(i);
            if (Character.isJavaIdentifierPart(ch)) {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public static String toSafeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        name = toJavaIdentifier(name);
        sb.append(name);
        if (SourceVersion.isKeyword(name)) {
            sb.append("$");
        }
        return sb.toString();
    }

    public static String validPackageName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException();
        }
        int idx = name.lastIndexOf('.');
        if (idx == -1) {
            validSimpleIdentifier(name);
        } else {
            validSimpleIdentifier(name.substring(idx + 1));
            validPackageName(name.substring(0, idx));
        }
        return name;
    }

    public static String validSimpleIdentifier(String name) {
        int length = name.length();
        if (length == 0) {
            throw new IllegalArgumentException();
        }

        int ch = name.codePointAt(0);
        if (length == 1 && ch == '_') {
            throw new IllegalArgumentException("'_' is no longer valid identifier.");
        }

        if (!Character.isJavaIdentifierStart(ch)) {
            throw new IllegalArgumentException("Invalid start character for an identifier: " + ch);
        }

        for (int i = 1; i < length; i++) {
            ch = name.codePointAt(i);
            if (!Character.isJavaIdentifierPart(ch)) {
                throw new IllegalArgumentException("Invalid character for an identifier: " + ch);
            }
        }
        return name;
    }

    public static String getSymbolInLib(Declaration d) {
        return d.getAttribute("AsmLabelAttr")
            .map(l -> l.get(0))
            .map(String.class::cast)
            .map(s -> isMacOS ? s.substring(1) : s)
            .orElse(d.name());
    }
}
