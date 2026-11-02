# AVF USB passthrough — device-string format RESOLVED
Source: com.google.android.virt APEX (build CP31.260618.005)
  - framework-virtualization.jar  (Java layer, jadx-decompiled)
  - bin/virtualizationservice     (native, string-extracted)
All facts below are read directly from the binaries, not inferred.

## Device-string format (DEFINITIVE)
Each element of usb_config.devices is a lowercase hex "vendor:product" USB ID.
Literal validation string in virtualizationservice:
    "Expected vendor:product hex string (e.g. 18d1:27a7)"
Supporting tokens in device_assignment path:
    idProduct, busnum, devnum, "Invalid vendor ID", "Invalid product ID"
    reads /sys/bus/usb/devices and /dev/bus/usb/

Example usb_config block for vm_config.json:
    "usb_config": {
      "controller": true,
      "devices": ["18d1:27a7"]
    }
(18d1 = Google vendor ID; use your lsusb vvvv:pppp values.)

## Full data path (all pure pass-through in Java)
app ConfigJson$UsbConfigJson.devices (String[])
  -> UsbConfig.Builder.setDevices(String[])            [app]
  -> VirtualMachineCustomImageConfig.UsbConfig         [framework jar]
       KEY_DEVICES="devices", stored in PersistableBundle, NO validation
  -> android.system.virtualizationservice.UsbConfig    [AIDL parcelable]
       fields: boolean controller; String[] devices;   NO validation
  -> native virtualizationservice / crosvm             [format enforced HERE]
No Java layer parses or validates the string. Grammar lives in native code only.

## THE GATE (critical constraint)
Native device_assignment.rs checks each requested device against an allow-list:
    /vendor/etc/avf/assignable_devices.xml
    schema: AssignableDeviceXml { dtbo_label, sysfs_path, device }
    "sysfs_path must be absolute"
    "<device> is not assignable"   <- refusal if not whitelisted
Consequence:
  - A USB device must be declared in assignable_devices.xml to be passed through.
  - That file is on the VENDOR partition (verified-boot protected, read-only).
  - Editing it is a vendor-image modification, NOT config injection, and cannot be
    done while preserving verified boot / platform signature state.

## Status of the three sub-problems
1. Format         : SOLVED  -> "vendor:product" hex (e.g. 18d1:27a7)
2. Injection point: SOLVED  -> usb_config.devices in files/linux/vm_config.json
                               (honored by the app's "custom config" branch)
3. Assignability  : BLOCKED -> requires /vendor/etc/avf/assignable_devices.xml
                               entry; vendor partition is RO under verified boot.

## Also present in the APEX payload (for reference)
javalib/: framework-virtualization.jar, service-virtualization.jar
bin/    : virtualizationservice, virtmgr, crosvm (+variants), fd_server, vm,
          vfio_handler, vmnic
etc/    : vm_profiles.json, microdroid.json, assignable_devices handling
lib64/  : libvirtualizationservice_jni.so, libvirtualmachine_jni.so, libavf.so
