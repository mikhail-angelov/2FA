# Keep the kotlinx-serialization machinery for the vault models even after shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.mikhail.authenticator.vault.** {
    *** Companion;
}
-keepclasseswithmembers class com.mikhail.authenticator.vault.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generates implementations at compile time; nothing to keep beyond its own rules.
# ML Kit ships consumer rules of its own.
