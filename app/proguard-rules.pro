# R8 rules for the release build (on top of proguard-android-optimize.txt).

# The IPC contract (rfanalyzer_ipc): its Parcelables cross to the digital extension by class name
# (the sealed DigitalDemodParams, the Event bundles), its AIDL stubs by interface descriptor. The
# extension keeps them the same way - every name must stay as it is in both apps, a renamed class
# fails in the extension with a ClassNotFoundException.
-keep class com.mantz_it.rfanalyzer_ipc.** { *; }
-keep interface com.mantz_it.rfanalyzer_ipc.** { *; }
