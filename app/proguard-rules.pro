# Speaker uses no reflection and no third-party libraries. Components declared in the manifest
# (activity, services, receivers) are kept automatically by R8.

# Keep line numbers so crash reports from Play Console are readable (upload mapping.txt there).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
