# Build

```
export NDK=/home/dennis/Android/ndk/29.0.13599879 
wget https://github.com/libusb/libusb/releases/download/v1.0.29/libusb-1.0.29.tar.bz2
tar xf libusb-1.0.29.tar.bz2
cd libusb-1.0.29/android/jni
$NDK/ndk-build
```

# Import into project

```
cd libusb/src/main/
mkdir jniLibs
cd jniLibs
rm -rf arm64-v8a armeabi-v7a x86 x86_64
mkdir arm64-v8a armeabi-v7a x86 x86_64
cp ~/libusb-1.0.29/android/libs/x86/libusb1.0.so x86/
cp ~/libusb-1.0.29/android/libs/x86_64/libusb1.0.so x86_64/
cp ~/libusb-1.0.29/android/libs/arm64-v8a/libusb1.0.so arm64-v8a/
cp ~/libusb-1.0.29/android/libs/armeabi-v7a/libusb1.0.so armeabi-v7a/
```
