# Add classes here when necessary.

-dontwarn android.bluetooth.**
-dontwarn sun.misc.Unsafe
-dontwarn javax.lang.model.element.Modifier
-dontwarn java.lang.ClassValue
-dontwarn org.checkerframework.checker.**
-dontwarn afu.org.checkerframework.checker.**

-keep class com.wristcursor.app.ui.devices.AvailableDevicesFragment {}
-keep class com.wristcursor.app.ui.devices.AboutFragment {}
-keep class com.wristcursor.app.ui.input.InputSettingsFragment {}

-keepclasseswithmembers class * {
    native <methods>;
}

-keepclasseswithmembers class com.wristcursor.app.sensors.SensorFusionJni {
    *;
}
