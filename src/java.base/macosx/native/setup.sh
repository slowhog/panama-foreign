rm -fr ../classes/jdk
CLANG_HOME=$HOME/lib/clang
CLANG_VER='9.0.0'
CLANG_INCLUDE="$CLANG_HOME/lib/clang/$CLANG_VER/include"
#jbind -C -isystem -C $CLANG_INCLUDE @jextract.args -n LibC java_base.h
#jbind -C -isystem -C $CLANG_INCLUDE @jextract.args @MacSymbols -n LibMacOS java_base_mac.h
jbind @jextract.args --max-depth=4 -n LibC java_base.h
jbind @jextract.args @MacSymbols --max-depth=4 -n LibMacOS java_base_mac.h
rm -fr ../classes/Users
