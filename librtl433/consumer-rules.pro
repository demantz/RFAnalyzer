# Keep the native bridge callback and entry points used by librtl433.
-keepclassmembers class com.mantz_it.librtl433.Rtl433Native {
    private void onDecodedJson(java.lang.String);
}
-keepclasseswithmembernames class com.mantz_it.librtl433.Rtl433Native {
    native <methods>;
}
