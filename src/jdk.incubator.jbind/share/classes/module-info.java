module jdk.incubator.jbind {
    requires jdk.incubator.jextract;
    requires jdk.internal.opt;
    requires java.logging;
    requires java.compiler;
    requires transitive jdk.incubator.foreign;

    exports jdk.incubator.jbind.core;

    provides java.util.spi.ToolProvider with
            jdk.incubator.jbind.Main.JBindToolProvider;
}