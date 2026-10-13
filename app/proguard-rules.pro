# The module hooks framework methods reflectively (Class.forName / getDeclaredMethod),
# so those member names must survive shrinking. Minification is disabled for the release
# build anyway (see app/build.gradle.kts); these rules are kept for anyone who turns it on.

-keep class app.hide.accessibility.hook.ModuleEntry { *; }
-keep class app.hide.accessibility.app.ModuleApp { *; }

# libxposed talks to the module through these entry points and Binder stubs.
-keep class io.github.libxposed.** { *; }

# The framework reflects into these system classes; keep our hook targets referencable.
-keepclassmembers class android.accessibilityservice.AccessibilityServiceInfo { *; }
