# Source this before building from WSL: `source scripts/env.sh`
# Reuses the JDK, Android SDK and Gradle cache already set up on this machine.
TOOLS="$HOME/.cache/sweepy-build"
export JAVA_HOME="$TOOLS/jdk"
export ANDROID_HOME="$TOOLS/sdk"
export GRADLE_USER_HOME="$TOOLS/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
