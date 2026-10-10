# R8 rules for the release build. The app uses no reflection beyond Kotlin property delegates and
# kotlinx.serialization (which ships its own rules), so the defaults are enough.

# Keep file names and line numbers so stack traces (Diagnostics, crash reports) stay readable;
# map them back with app/build/outputs/mapping/release/mapping.txt (keep it with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
