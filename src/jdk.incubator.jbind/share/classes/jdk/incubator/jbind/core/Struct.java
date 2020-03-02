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

package jdk.incubator.jbind.core;

import java.lang.invoke.VarHandle;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;

public abstract class Struct<T extends Struct<T>> {
    private final MemoryAddress addr;

    protected Struct(MemoryAddress addr) {
        this.addr = addr;
    }

    public abstract GroupLayout getLayout();

    public MemoryAddress ptr() {
        return addr;
    };

    protected final MemoryAddress getFieldAddr(String name) {
        return addr.addOffset(getLayout().offset(MemoryLayout.PathElement.groupElement(name)) >> 3);
    }

    protected final VarHandle getFieldHandle(String name, Class<?> carrier) {
        return getLayout().varHandle(carrier, MemoryLayout.PathElement.groupElement(name));
    }
}
