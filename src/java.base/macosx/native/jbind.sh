JBIND=$HOME/ws/jextract_min
java -p $JBIND/lib/jopt-simple-5.0.4.jar:$JBIND/build/production \
    -Djava.library.path=$HOME/lib \
    --add-exports jdk.incubator.foreign/jdk.incubator.foreign.unsafe=jextract.api,jbind \
    --module jbind/com.oracle.tools.jbind.Main \
    $@
