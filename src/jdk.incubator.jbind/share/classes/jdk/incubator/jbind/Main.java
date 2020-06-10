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
import jdk.incubator.jextract.JextractTask;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.PatternSyntaxException;
import java.util.spi.ToolProvider;
import javax.tools.JavaFileObject;
import jdk.internal.joptsimple.OptionException;
import jdk.internal.joptsimple.OptionParser;
import jdk.internal.joptsimple.OptionSet;
import jdk.internal.joptsimple.util.KeyValuePair;

public class Main {
    public static final boolean DEBUG = Boolean.getBoolean("jbind.debug");

    // error codes
    private static final int OPTION_ERROR  = 1;
    private static final int INPUT_ERROR   = 2;
    private static final int OUTPUT_ERROR  = 3;
    private static final int RUNTIME_ERROR = 4;

    private final PrintWriter out;
    private final PrintWriter err;

    private Main(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    private void printHelp(OptionParser parser) {
        try {
            parser.printHelpOn(err);
        } catch (IOException ex) {
            throw new FatalError(RUNTIME_ERROR, ex);
        }
    }

    private OptionParser setupOptionParser() {
        OptionParser parser = new OptionParser(false);

        // short optiones starts with -
        parser.accepts("C", Log.format("help.C")).withRequiredArg();
        parser.accepts("I", Log.format("help.I")).withRequiredArg();
        parser.acceptsAll(List.of("L", "library-path"), Log.format("help.L")).withRequiredArg();
        parser.accepts("d", Log.format("help.d")).withRequiredArg();
        // option is expected to specify paths to load shared libraries
        parser.accepts("l", Log.format("help.l")).withRequiredArg();
        parser.accepts("o", Log.format("help.o")).withRequiredArg();
        parser.acceptsAll(List.of("t", "target-package"), Log.format("help.t")).withRequiredArg();
        parser.acceptsAll(List.of("n", "name"), Log.format("help.n")).withRequiredArg();
        parser.acceptsAll(List.of("?", "h", "help"), Log.format("help.h")).forHelp();

        // long options starts with --
        parser.accepts("condy", Log.format("help.condy"));
        parser.accepts("dry-run", Log.format("help.dry_run"));
        parser.accepts("exclude-symbols", Log.format("help.exclude", "symbols")).withRequiredArg();
        parser.accepts("include-symbols", Log.format("help.include", "symbols")).withRequiredArg();
        parser.accepts("exclude-headers", Log.format("help.exclude", "headers")).withRequiredArg();
        parser.accepts("include-headers", Log.format("help.include", "headers")).withRequiredArg();
        parser.accepts("log", Log.format("help.log")).withRequiredArg();
        parser.accepts("package-map", Log.format("help.package_map")).withRequiredArg();
        parser.accepts("missing-symbols", Log.format("help.missing_symbols")).withRequiredArg();
        parser.accepts("no-locations", Log.format("help.no.locations"));
        parser.accepts("src-dump-dir", Log.format("help.src_dump_dir")).withRequiredArg();
        parser.accepts("static-forwarder", Log.format("help.static_forwarder")).
                withRequiredArg().ofType(boolean.class);
        parser.nonOptions(Log.format("help.non.option"));

        return parser;
    }

    private OptionSet parseOptions(OptionParser parser, String[] args) {
        if (args.length == 0) {
            printHelp(parser);
            throw new FatalError(OPTION_ERROR);
        }

        try {
            OptionSet optionSet = parser.parse(args);

            if (optionSet.has("h")) {
                printHelp(parser);
                return null;
            }

            if (optionSet.nonOptionArguments().isEmpty()) {
                throw new FatalError(OPTION_ERROR, Log.format("err.no.input.files"));
            }

            return optionSet;
        } catch (OptionException oe) {
            printHelp(parser);
            throw new FatalError(OPTION_ERROR, oe);
        }
    }

    private Log setupLogger(OptionSet optionSet) {
        Log log;
        if (optionSet.has("log")) {
            log = Log.of(out, err, Level.parse((String) optionSet.valueOf("log")));
        } else {
            log = Log.of(out, err, Level.WARNING);
        }
        return log;
    }

    private void tryAddPatterns(OptionSet options, String command, Consumer<String> adder) {
        try {
            options.valuesOf(command).forEach(sym -> adder.accept((String) sym));
        } catch (PatternSyntaxException pse) {
            throw new FatalError(OPTION_ERROR, Log.format("pattern.error", command, pse.getMessage()));
        }
    }


    public Configurations buildContext(Log log, OptionSet options) {
        String targetPackage = options.has("t") ? (String) options.valueOf("t") : "";
        if (!targetPackage.isEmpty()) {
            NamingUtils.validPackageName(targetPackage);
        }
        Configurations.Builder builder = new Configurations.Builder(log, targetPackage);

        boolean librariesSpecified = options.has("l");
        if (librariesSpecified) {
            for (Object arg : options.valuesOf("l")) {
                String lib = (String)arg;
                if (lib.indexOf(File.separatorChar) != -1) {
                    throw new FatalError(OPTION_ERROR, Log.format("l.name.should.not.be.path", lib));
                }
                builder.addLibraryName(lib);
            }
        }

        if (options.has("n")) {
            String name = (String) options.valueOf("n");
            System.err.println("Main cls name assigned: " + name);
            builder.setMainClsName(
                    NamingUtils.validSimpleIdentifier(name));
        }

        if (options.has("L")) {
            List<?> libpaths = options.valuesOf("L");
            if (librariesSpecified) {
                libpaths.forEach(p -> builder.addLibraryPath((String) p));
            } else {
                // "L" with no "l" option!
                err.println(Log.format("warn.L.without.l"));
            }
        }

        if (options.has("package-map")) {
            options.valuesOf("package-map").forEach(p -> processPackageMapping(p, builder));
        }

        builder.useCondy(options.has("condy"));

        for (Object header : options.nonOptionArguments()) {
            Path p = Paths.get((String)header);
            if (!Files.isReadable(p)) {
                throw new Main.FatalError(INPUT_ERROR, Log.format("cannot.read.header.file", header));
            }
            builder.addSourceFile(p);
        }

        return builder.build();
    }

    private static void processPackageMapping(Object arg, Configurations.Builder builder) {
        String str = (String) arg;
        String pkgName;
        KeyValuePair kv = KeyValuePair.valueOf(str);
        Path p = Paths.get(kv.key);
        pkgName = kv.value;

        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(Log.format("not.a.directory", kv.key));
        }

        NamingUtils.validPackageName(pkgName);
        builder.usePackageForFolder(p, pkgName);
    }

    private void runInternal(String[] args) {
        OptionParser parser = setupOptionParser();
        OptionSet optionSet = parseOptions(parser, args);
        if (optionSet == null) {
            // Done with -h --help
            return;
        }

        final Log log = setupLogger(optionSet);
        final Configurations ctx = buildContext(log, optionSet);


        List<String> parsingOptions = new ArrayList<>();
        if (optionSet.has("I")) {
            optionSet.valuesOf("I").forEach(p -> parsingOptions.add("-I" + p));
        }
        if (optionSet.has("C")) {
            optionSet.valuesOf("C").forEach(p -> parsingOptions.add((String) p));
        }
        Path builtinInc = Paths.get(System.getProperty("java.home"), "conf", "jextract");
        parsingOptions.add("-I" + builtinInc);

        JextractTask jextract = JextractTask.newTask(false, ctx.getSources().toArray(Path[]::new));
        Declaration.Scoped root = jextract.parse(parsingOptions.toArray(String[]::new));

        PatternFilter.Builder headers = PatternFilter.builder();
        tryAddPatterns(optionSet, "include-headers", headers::addInclude);
        tryAddPatterns(optionSet, "exclude-headers", headers::addExclude);

        PatternFilter.Builder symbols = PatternFilter.builder();
        tryAddPatterns(optionSet, "include-symbols", symbols::addInclude);
        tryAddPatterns(optionSet, "exclude-symbols", symbols::addExclude);

        List<? extends JavaFileObject> files = JavaSourceFactory.of(ctx)
                .withHeaderFilter(headers.buildPathMatcher())
                .withSymbolFilter(symbols.buildRegexMatcher())
                .generate(root);

        Path binaryOutputDir = Paths.get(optionSet.has("d") ? (String) optionSet.valueOf("d") : ".");
        Path sourceOutputDir = optionSet.has("src-dump-dir") ? Paths.get((String) optionSet.valueOf("src-dump-dir")) : binaryOutputDir;
        try {
            new Writer(binaryOutputDir, sourceOutputDir, files).writeAll(true);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private int run(String[] args) {
        try {
            runInternal(args);
        } catch (FatalError e) {
            err.println(e.getMessage());
            if (Main.DEBUG) {
                e.printStackTrace(err);
            }
            return e.errorCode;
        }

        return 0;
    }

    public static void main(String... args) {
        Main instance = new Main(Log.defaultOut(), Log.defaultErr());
        System.exit(instance.run(args));
    }

    public static class JBindToolProvider implements ToolProvider {
        @Override
        public String name() {
            return "jbind";
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... args) {
            // defensive check to throw security exception early.
            // Note that the successful run of jextract under security
            // manager would require far more permissions like loading
            // library (clang), file system access etc.
            if (System.getSecurityManager() != null) {
                System.getSecurityManager().
                        checkPermission(new RuntimePermission("jextract"));
            }

            Main instance = new Main(out, err);
            return instance.run(args);
        }
    }

    private static class FatalError extends Error {
        private static final long serialVersionUID = 0L;

        public final int errorCode;

        public FatalError(int errorCode) {
            this.errorCode = errorCode;
        }

        public FatalError(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public FatalError(int errorCode, Throwable cause) {
            super(cause);
            this.errorCode = errorCode;
        }

        public FatalError(int errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

    }
}
