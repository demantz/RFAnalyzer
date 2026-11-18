# libusb

`libusb/` holds unmodified files from https://github.com/libusb/libusb at the commit in
`GIT_COMMIT`, keeping upstream's directory layout. `CMakeLists.txt` builds them into
`libusb1.0.so`; the SDR driver modules consume it via Prefab (`find_package(libusb)`,
target `libusb::usb`).

# Upgrade

Only the files needed for Android are imported (the list in upstream's
`android/jni/libusb.mk` plus their headers):

```
cd libusb/src/main/cpp
rm -rf libusb && mkdir libusb
git -C <libusb clone> archive <commit> COPYING android/config.h \
    libusb/core.c libusb/descriptor.c libusb/hotplug.c libusb/io.c libusb/sync.c \
    libusb/strerror.c libusb/libusb.h libusb/libusbi.h libusb/version.h libusb/version_nano.h \
    libusb/os/linux_usbfs.c libusb/os/linux_usbfs.h libusb/os/linux_netlink.c \
    libusb/os/events_posix.c libusb/os/events_posix.h \
    libusb/os/threads_posix.c libusb/os/threads_posix.h | tar x -C libusb
```

Then update `GIT_COMMIT` and compare upstream's `android/jni/libusb.mk` against the source
list and flags in `CMakeLists.txt`.
