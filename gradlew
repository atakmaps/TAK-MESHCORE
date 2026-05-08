#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

# TPC/CI often passes --init-script /root/.gradle/init.d/00-tak-artifactory.gradle
# for Artifactory credentials; some images omit that file and Gradle aborts before
# any project script runs. If the path is missing, create a no-op Groovy script.
# If /root is not writable (common when running as non-root), remap GRADLE_OPTS
# references for that path to a user-writable fallback under $HOME.
ensureOptionalTakArtifactoryInit() {
    TAK_ROOT_INIT="/root/.gradle/init.d/00-tak-artifactory.gradle"
    TAK_FALLBACK_INIT="${HOME}/.gradle/init.d/00-tak-artifactory.gradle"

    for _path in "${HOME}/.gradle/init.d/00-tak-artifactory.gradle" \
        "/root/.gradle/init.d/00-tak-artifactory.gradle"
    do
        if [ -f "$_path" ]; then
            continue
        fi
        _dir=$(dirname "$_path")
        if mkdir -p "$_dir" 2>/dev/null && [ -w "$_dir" ]; then
            cat > "$_path" <<'TAK_INIT_EOF' 2>/dev/null || true
// No-op: builder referenced this init script but it was absent on the image.
TAK_INIT_EOF
        fi
    done

    if [ ! -f "$TAK_ROOT_INIT" ] && [ -f "$TAK_FALLBACK_INIT" ]; then
        GRADLE_OPTS=`printf '%s' "$GRADLE_OPTS" | sed "s|$TAK_ROOT_INIT|$TAK_FALLBACK_INIT|g"`
    fi
}
ensureOptionalTakArtifactoryInit

# Some CI systems pass --init-script /root/.gradle/init.d/00-tak-artifactory.gradle
# directly as Gradle CLI args (not via GRADLE_OPTS). If /root is unwritable and the
# file is absent, remap those args to the fallback script under $HOME.
if [ ! -f "$TAK_ROOT_INIT" ] && [ -f "$TAK_FALLBACK_INIT" ]; then
    quote_arg() {
        printf '%s' "$1" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/'/"
    }
    remapped_args=""
    while [ "$#" -gt 0 ]; do
        arg="$1"
        shift
        case "$arg" in
            --init-script|-I)
                if [ "$#" -gt 0 ] && [ "$1" = "$TAK_ROOT_INIT" ]; then
                    remapped_args="$remapped_args `quote_arg "$arg"` `quote_arg "$TAK_FALLBACK_INIT"`"
                    shift
                    continue
                fi
                remapped_args="$remapped_args `quote_arg "$arg"`"
                ;;
            --init-script="$TAK_ROOT_INIT")
                remapped_args="$remapped_args `quote_arg "--init-script=$TAK_FALLBACK_INIT"`"
                ;;
            -I="$TAK_ROOT_INIT")
                remapped_args="$remapped_args `quote_arg "-I=$TAK_FALLBACK_INIT"`"
                ;;
            *)
                remapped_args="$remapped_args `quote_arg "$arg"`"
                ;;
        esac
    done
    eval "set -- $remapped_args"
fi

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
            MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then
            warn "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    else
        warn "Could not query maximum file descriptor limit: $MAX_FD_LIMIT"
    fi
fi

# For Darwin, add options to specify how the application appears in the dock
if $darwin; then
    GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if [ "$cygwin" = "true" -o "$msys" = "true" ] ; then
    APP_HOME=`cygpath --path --mixed "$APP_HOME"`
    CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`
    JAVACMD=`cygpath --unix "$JAVACMD"`

    # We build the pattern for arguments to be converted via cygpath
    ROOTDIRSRAW=`find -L / -maxdepth 1 -mindepth 1 -type d 2>/dev/null`
    SEP=""
    for dir in $ROOTDIRSRAW ; do
        ROOTDIRS="$ROOTDIRS$SEP$dir"
        SEP="|"
    done
    OURCYGPATTERN="(^($ROOTDIRS))"
    # Add a user-defined pattern to the cygpath arguments
    if [ "$GRADLE_CYGPATTERN" != "" ] ; then
        OURCYGPATTERN="$OURCYGPATTERN|($GRADLE_CYGPATTERN)"
    fi
    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    i=0
    for arg in "$@" ; do
        CHECK=`echo "$arg"|egrep -c "$OURCYGPATTERN" -`
        CHECK2=`echo "$arg"|egrep -c "^-"`                                 ### Determine if an option

        if [ $CHECK -ne 0 ] && [ $CHECK2 -eq 0 ] ; then                    ### Added a condition
            eval `echo args$i`=`cygpath --path --ignore --mixed "$arg"`
        else
            eval `echo args$i`="\"$arg\""
        fi
        i=`expr $i + 1`
    done
    case $i in
        0) set -- ;;
        1) set -- "$args0" ;;
        2) set -- "$args0" "$args1" ;;
        3) set -- "$args0" "$args1" "$args2" ;;
        4) set -- "$args0" "$args1" "$args2" "$args3" ;;
        5) set -- "$args0" "$args1" "$args2" "$args3" "$args4" ;;
        6) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" ;;
        7) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" ;;
        8) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" "$args7" ;;
        9) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" "$args7" "$args8" ;;
    esac
fi

# Escape application args
save () {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/" ; done
    echo " "
}
APP_ARGS=`save "$@"`

# Collect all arguments for the java command, following the shell quoting and substitution rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradle.wrapper.GradleWrapperMain "$APP_ARGS"

exec "$JAVACMD" "$@"
