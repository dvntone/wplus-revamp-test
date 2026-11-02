# AVF Terminal — vm_config.json / usb_config findings
Source: classes.dex from VMTERMINALAPPGOOGLE.APK
Package com.android.virtualization.terminal, versionCode 37, targetSdk 37
Extracted via androguard 4.1.4 (bytecode-level, not inferred)

## Config file path
Built in InstalledImage$Companion.getDefault(Context):
  getFilesDir() / "linux" / "vm_config.json"
Absolute:
  /data/data/com.android.virtualization.terminal/files/linux/vm_config.json
  (== /data/user/0/com.android.virtualization.terminal/files/linux/vm_config.json)
Stored on field: InstalledImage->configPath : java.nio.file.Path

Sibling paths under files/linux/:
  root_part           (InstalledImage->rootPartition)
  root_part_backup    (InstalledImage->backupFile)
  vm_config.json      (InstalledImage->configPath)
  completed           (InstalledImage->marker)
  cidata.build_id     (InstalledImage->cidataBuildIdPath)

## Read/parse path
ConfigJson$Companion.from(Context, Path):
  Log.i("VmTerminalApp", "Parsing VM config at " + path)
  FileReader(path.toFile())
  -> ConfigJson$Companion.replaceKeywords(Reader, Context)   # keyword templating pass
  -> Gson().fromJson(text, ConfigJson.class)                 # lenient: extra keys ignored
Caller: new2.core.VmController$start$1.invokeSuspend()
  reads InstalledImage->configPath, calls ConfigJson.Companion.from(...)
  Branch string present:
    "Custom config provided; skipping default memory, disk, and UI overrides."

## usb_config schema (class ConfigJson$UsbConfigJson)
JSON key on parent ConfigJson: "usb_config"
Fields:
  controller : boolean            (Z)
  devices    : String[]           ([Ljava/lang/String;)

Example block:
  "usb_config": {
    "controller": true,
    "devices": ["<device-string>", "<device-string>"]
  }

## setDevices mapping (UsbConfigJson.toConfig() bytecode)
  new VirtualMachineCustomImageConfig$UsbConfig$Builder()
    .setController(this.controller)      // boolean, verbatim
    .setDevices(this.devices)            // String[], verbatim — NO transformation
    .build()
=> Each element of "devices" is passed unchanged to the platform
   VirtualMachineCustomImageConfig.UsbConfig.Builder.setDevices(String[]).
   The device-string format is defined by that framework builder, not by the app.

## Signature / permission implication
- This is CONFIG DATA, not code. classes.dex and the signing cert are untouched.
- Platform-signature grants (MANAGE_VIRTUAL_MACHINE_USB, USE_CUSTOM_VIRTUAL_MACHINE,
  MANAGE_VIRTUAL_MACHINE) remain intact because the app's own signed code reads the file.
- Caveat: files/ is app-private storage. Writing vm_config.json there requires the
  Terminal app's own config/import flow, or a shell with that UID / root. This is a
  delivery constraint, not a signature-breaking one.

## Parent ConfigJson keys (for a complete custom file)
name, kernel, initrd, params, bootloader, cpu_topology, memory_mib,
platform_version, console_input_device, console_out, connect_console,
network, debuggable, isProtected, hugepages, auto_memory_balloon,
use_virtio_console, boot_timeout_secs,
input{}, audio{}, disks[], sharedPath[], display{}, gpu{}, smbios{},
pflash[], usb_config{}
