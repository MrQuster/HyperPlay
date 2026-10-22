# Сохраняем классы системных аудиоэффектов Android и Media3
-keep class android.media.audiofx.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Сохраняем рефлексию для определения оболочки и Live Updates
-keep class android.os.SystemProperties { *; }
-keepclassmembers class android.app.NotificationManager {
    public boolean canPostPromotedNotifications();
}

# Сохраняем модели данных и JSON
-keep class org.json.** { *; }
-keep class io.github.mrquster.hyperplay.data.** { *; }

# Вырезаем отладочные логи из релизной сборки
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
