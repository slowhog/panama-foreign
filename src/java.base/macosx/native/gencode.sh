rm -fr ../classes/jdk/inetnal/panama
SYSROOT=`xcrun --show-sdk-path`
COMMON_ARGS="-t jdk.internal.panama -C -isysroot -C $SYSROOT -J-Dforeign.restricted=permit --src-dump-dir=../classes"
jbind -n LibC $COMMON_ARGS @posixSymbols java_base.h
jbind -n LibMacOS $COMMON_ARGS @MacSymbols java_base_mac.h
