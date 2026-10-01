# Keep the kotlinx-serialization machinery for the vault models even after shrinking.
# ML Kit и R8.
#
# Библиотека находит свои компоненты по именам классов, записанным строками в манифесте:
# провайдер инициализации com.google.mlkit.common.internal.MlKitInitProvider и регистраторы
# вида com.google.firebase.components:com.google.mlkit.vision.barcode.internal.BarcodeRegistrar.
# R8 не считает такие строки ссылками на классы и переименовывает их (в карте видно
# SharedPrefManager -> hd2, MlKitContext -> se1, zzmj -> bv3). Инициализация ML Kit тогда
# проходит неполно: в конструктор внутреннего zzmj вместо SharedPrefManager приходит null,
# и приложение падает ещё до первой попытки распознавания — в сообщении это выглядит как
# «проверено вариантов 0» и NullPointerException с getClass() на null.
#
# Проверено на релизной сборке: с этими правилами импорт из скриншота проходит весь путь
# (варианты 1..7), без правил — падал на первом же.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

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
