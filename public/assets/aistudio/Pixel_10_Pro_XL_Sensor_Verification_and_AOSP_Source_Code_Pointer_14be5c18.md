# Verification of Pixel 10 Pro XL Sensor List and Pointers to Android/AOSP Source Code

## Executive Summary

The user-supplied sensor list for the Google Pixel 10 Pro XL (codename **mustang**, SoC platform codename **laguna**, Tensor G5) is **substantively correct** when cross-checked against Google's official spec sheet, but several items need clarification (the fingerprint type and the satellite SOS hardware are not labeled on Google's spec sheet the way the list claims, the temperature sensor's "IR" attribute is correct in fact but absent from Google's spec page wording, and "Bluetooth 6.0" is more precisely "Bluetooth v6"). I was also unable to find explicit Pixel‑10‑specific HAL or device‑tree code in public AOSP because **Google stopped publishing Pixel device trees to AOSP starting with Android 16** — Pixel 10 has never had an AOSP device tree. [XDA Forums](https://xdaforums.com/t/build-aosp-gsi-from-source-and-flash-it-with-dsu.4749904/) The kernel sources, the shared Pixel HAL code (`hardware/google/pixel`, `device/google/gs-common`), and the shared camera HAL (`hardware/google/camera`) that the Pixel 10 inherits from are still available on android.googlesource.com and via GrapheneOS prebuilt mirrors. The report below identifies the relevant code paths in detail.

---

## 1. Verification of the Claimed Sensor List Against Official Sources

The Google Store official Pixel 10 Pro / Pro XL spec sheet (`store.google.com/product/pixel_10_pro_specs`) lists the Pixel 10 Pro XL sensors in two sections — a "Sensors" block for system sensors and a "Rear camera" block that includes camera-bar sensors. What follows is the actual contents of those sections, used to validate the user's list.

**System "Sensors" block on the official spec sheet:**
- Proximity sensor
- Ambient light sensor
- Accelerometer
- Gyrometer
- Magnetometer
- Barometer
- Temperature sensor [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)

**"All rear cameras" block on the official spec sheet:**
- Multi-zone LDAF (laser detect auto focus) sensor
- Spectral and flicker sensor
- Optical + electronic image stabilization on wide and telephoto [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)

**"Connectivity and location" block:**
- Wi-Fi 7 (802.11be) 2x2 MIMO with 2.4/5/6 GHz
- Bluetooth v6 with antenna diversity
- NFC, Google Cast, Thread networking
- Ultra‑Wideband (UWB) chip for accurate ranging and spatial orientation
- Dual‑Band GNSS (GPS, GLONASS, Galileo) [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)

**"Authentication" block:** Fingerprint Unlock, Face Unlock, Pattern/PIN/password. [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)

**"Safety" block:** Satellite SOS (and Emergency SOS, Crisis Alerts, etc.). [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)

### Item-by-item verdict

| # | Claim in user's list | Verdict | Notes / Source |
|---|---|---|---|
| 1 | Temperature Sensor (IR, rear camera bar, FDA-cleared) | **Confirmed in fact, partially on spec sheet.** | The official spec sheet lists "Temperature sensor" but doesn't say "IR"/"camera bar". Independent reporting (Tom's Guide, Fierce Biotech, How‑To Geek, Gadget Hacks) confirms the Pixel 10 Pro/Pro XL re‑uses the same rear‑bar IR sensor first introduced on the Pixel 8 Pro, that the body‑temperature mode received FDA De Novo clearance (granted to Fitbit Health Solutions in late 2023/early 2024), and that the LDAF is used to enforce the ~½‑inch standoff distance during measurement. [Gadget Hacks](https://android.gadgethacks.com/news/google-removing-pixel-thermometer-feature-for-pixel-glow-leds/) |
| 2 | Ultrasonic Fingerprint Sensor (under display, 3D sonic) | **Confirmed by reporting; not labeled on the spec sheet.** | The Google spec sheet only says "Fingerprint Unlock". Multiple reports (Biometric Update, GSMArena, Android Headlines, Android Police) confirm the Pixel 9 introduced an ultrasonic Qualcomm 3D Sonic Gen 2 under‑display sensor [Android Faithful](https://www.androidfaithful.com/google-pixel-9/) [Biometric Update](https://www.biometricupdate.com/202506/google-upgrading-ultrasonic-biometric-sensor-for-pixel-10-smartphones) and that the Pixel 10 series ships with an upgraded ultrasonic sensor (faster than the Pixel 9's). [Android Headlines](https://www.androidheadlines.com/google-pixel-10-upgraded-ultrasonic-fingerprint-sensor) [GSMArena](https://www.gsmarena.com/google_pixel_10_series_upgraded_ultrasonic_fingerprint_scanner-news-68296.php) The exact supplier for the Pixel 10 has not been formally confirmed by Google. |
| 3 | Barometer | **Confirmed** on the spec sheet. |
| 4 | Magnetometer (Compass) | **Confirmed** on the spec sheet (listed as "Magnetometer"). |
| 5 | Accelerometer | **Confirmed** on the spec sheet. |
| 6 | Gyrometer (Gyroscope) | **Confirmed** on the spec sheet (Google calls it "Gyrometer"). |
| 7 | Proximity Sensor | **Confirmed** on the spec sheet. |
| 8 | Ambient Light Sensor | **Confirmed** on the spec sheet. |
| 9 | UWB Chip | **Confirmed** on the spec sheet. (Note: only the Pro / Pro XL / Pro Fold ship UWB; the base Pixel 10 does not.) [Android Police](https://www.androidpolice.com/pixel-10-uwb-features/) |
| 10 | LDAF (Laser Detect Auto Focus) | **Confirmed**, and the spec sheet specifically calls it "Multi‑zone LDAF". |
| 11 | Spectral and Flicker Sensor (camera bar) | **Confirmed** on the spec sheet. |
| 12 | Satellite SOS hardware | **Confirmed in the spec sheet's Safety section.** Skylo's August 20, 2025 press release confirms the Pixel 10 Series uses Skylo's NB‑NTN [IoT Business News](https://iotbusinessnews.com/2025/08/21/20265-google-skylo-bring-satellite-connectivity-to-pixel-10-and-launch-pixel-watch-4-with-2-way-emergency-messaging/) (3GPP Rel‑17 NTN) service. The exact modem used in the Pixel 10 has been reported (by Android Authority and others) to be a MediaTek T900 5G modem paired with the TSMC‑fabbed Tensor G5; [Fone Arena](https://www.fonearena.com/blog/450384/google-pixel-10-camera-specs-leak.html) this should be treated as reporting rather than an official Google statement. There is no separate "satellite SOS chip" — the capability is delivered through the cellular modem firmware using the NTN bands. |
| 13 | Bluetooth 6.0 | **Substantially confirmed.** Google calls it "Bluetooth v6 with antenna diversity"; [google](https://store.google.com/product/pixel_10_pro_specs?hl=en-US) "Bluetooth 6.0" is the colloquial way of writing it. |
| 14 | Wi-Fi 7 | **Confirmed** on the spec sheet (802.11be, 2x2 MIMO, tri‑band). |
| 15 | NO Soli/Project radar chip | **Confirmed.** Soli radar shipped only on the Pixel 4/4 XL (2019). [Google Support](https://support.google.com/pixelphone/answer/7157629?hl=en) It is absent from every Pixel from the Pixel 5 onward, including all Pixel 10 models. The spec sheet lists no radar sensor. |

### Items the user's list does NOT mention but that the spec sheet (or teardowns) do

- **NFC and Thread** radios.
- **Dual‑band GNSS** (GPS / GLONASS / Galileo).
- **Class‑3 Face Unlock** (the front cameras + on‑device ML stack used for biometric authentication).
- **Hall sensor / magnetic array for Pixelsnap (Qi2.2)** — the Pixel 10 Pro XL has a circular array of magnets behind the back glass (visible in JerryRigEverything's transparent‑mod teardown reported by Phone Repair Guru and Gizmochina) for Qi2.2 wireless charging up to 25 W. This is a hardware feature with software‑detectable accessory recognition but is not strictly a "sensor" in Android's `Sensor` API sense.
- **Capacitive grip / hall switch** for case detection are not separately enumerated by Google.

### Items in the user's list that need flagging

- **"Temperature Sensor (IR, rear camera bar, FDA‑cleared)":** Accurate as a description of the actual hardware, but Google's spec sheet does not label it "IR" or "camera bar" and does not advertise FDA clearance on the Pixel 10 spec page itself. The De Novo FDA clearance was granted to Fitbit (Google) for the Thermometer app on the Pixel 8 Pro [Fierce Biotech](https://www.fiercebiotech.com/medtech/google-captures-fda-de-novo-clearance-pixel-smartphone-thermometer-app) and has carried forward to the Pixel 9 Pro/Pro XL and Pixel 10 Pro/Pro XL.
- **"Bluetooth 6.0":** The marketing string Google uses is "Bluetooth v6"; the BT SIG core specification is officially "Bluetooth Core Specification 6.0". The user's claim is correct but the spec page string is "v6".
- **"Ultrasonic Fingerprint Sensor (3D sonic)":** Not stated as such on Google's spec sheet, but consistent with the Pixel 9 design and corroborated by multiple sources (Android Headlines, GSMArena, Android Police, Biometric Update). Treat the "3D Sonic Gen 2/3" attribution as reporting until confirmed by an FCC teardown; the "ultrasonic" attribution is well established.
- **"Satellite SOS hardware":** Strictly speaking there is no separate sensor or chip; satellite SOS is implemented via the NB‑NTN capability of the cellular modem and the Skylo NTN service. It is correctly listed under Safety, not Sensors, on Google's spec sheet.

Conclusion of the verification step: the user's list is correct in the items it claims and is not missing anything that Google publicly markets as a Pixel 10 Pro XL sensor.

---

## 2. AOSP / Pixel Source Code That Interfaces With These Sensors

### 2.1 Important caveat about Pixel 10 device trees

Beginning with Android 16 (the OS the Pixel 10 launched with), **Google removed Pixel device trees from AOSP**. Pixel 10 has never had a public AOSP device tree under `device/google/`, and Google is steering external developers toward Cuttlefish virtual reference devices instead [XDA Forums](https://xdaforums.com/t/build-aosp-gsi-from-source-and-flash-it-with-dsu.4749904/) (this is documented on `source.android.com/docs/setup/build/building-pixel-kernels` and reproduced in XDA build guides). What is still public for the Pixel 10 Pro XL is:

- **Kernel sources (6.6 LTS)** for the laguna (Pixel 10) family, mirrored by GrapheneOS at `gitlab.com/grapheneos/kernel_pixel_muzel.git` (branch `16-qpr2`) [GrapheneOS](https://grapheneos.org/build) and as kernel‑prebuilts at `github.com/GrapheneOS/device_google_laguna-kernels_6.6`. [GrapheneOS](https://grapheneos.org/source) The kernel codename "muzel" covers `frankel` (Pixel 10), `blazer` (Pixel 10 Pro) and `mustang` (Pixel 10 Pro XL); [GrapheneOS](https://grapheneos.org/build) the Fold (rango) uses a separate variant. Per GrapheneOS, the kernel SoC platform name is `laguna`, while the SoC for Pixel 8/9 was `zuma` (Tensor G3/G4) and `gs101` (Tensor G1 on Pixel 6).
- **The shared HAL and middleware repos** that Pixel 8/9/10 all depend on:
  - `platform/hardware/google/pixel` — Pixel‑specific HAL implementations: `https://android.googlesource.com/platform/hardware/google/pixel/`
  - `platform/hardware/google/camera` — Google Camera HAL (the GCH/Google Camera HAL framework around which Pixel camera HALs are built): `https://android.googlesource.com/platform/hardware/google/camera/`
  - `device/google/gs-common` — shared "Google SoC common" device tree: [github](https://github.com/GrapheneOS/device_google_gs-common) `https://android.googlesource.com/device/google/gs-common/`
  - `device/google/zuma` — Pixel 8 family device tree (still public): `https://android.googlesource.com/device/google/zuma/`
- **AOSP HAL interface definitions** that any Pixel HAL must implement:
  - Camera AIDL: `hardware/interfaces/camera/{provider,device,metadata,common}/aidl/` [Android Open Source Project](https://source.android.com/docs/core/camera/camera3) at `https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/master/camera/`
  - Sensors AIDL: `hardware/interfaces/sensors/aidl/`
  - UWB HAL: `hardware/interfaces/uwb/` [Android Open Source Project](https://source.android.com/docs/core/connect/uwb)
  - Fingerprint AIDL: `hardware/interfaces/biometrics/fingerprint/aidl/`
  - Thermal AIDL: `hardware/interfaces/thermal/aidl/`

### 2.2 Camera — the highest‑value subtree for this report

Pixel devices use Google's own camera HAL (Google Camera HAL, "GCH"), not a vendor camera HAL written by a chipset partner. The shared GCH lives at:

- **Repository root:** `https://android.googlesource.com/platform/hardware/google/camera/`
- **AIDL service entry point** (the binderized service the framework's `CameraService` actually talks to): `common/hal/aidl_service/` — this is the reference AIDL Camera Provider HAL implementation; [Android Open Source Project](https://source.android.com/docs/core/camera/camera3) the file `AidlCameraProvider.cpp/.h` and the `aidl_service.cpp` start the service with `ServiceManager::addService("android.hardware.camera.provider.ICameraProvider/internal/0", ...)`.
- **Core Google Camera HAL (capture sessions, ZSL, HDR+, Night Sight pipeline glue):** `common/hal/google_camera_hal/` — files of interest include `hdrplus_capture_session.cc`, `rgbird_capture_session.cc`, [Google](https://android.googlesource.com/platform/hardware/google/camera/+/817184b) `zsl_snapshot_capture_session.cc`, [Google](https://android.googlesource.com/platform/hardware/google/camera/+/d166c12) and `result_dispatcher.cc/.h`. These are the same files quoted in commits viewable at `https://android.googlesource.com/platform/hardware/google/camera/+/817184b` and `.../d166c12`.
- **Common HAL utilities and metadata helpers:** `common/hal/utils/` (e.g. `camera_blob.h` at `https://android.googlesource.com/platform/hardware/google/camera/+/refs/heads/main/common/hal/utils/camera_blob.h`). [Google](https://android.googlesource.com/platform/hardware/google/camera/+/refs/heads/main/common/hal/utils/camera_blob.h)
- **Reference EmulatedCamera HWL** (useful for understanding the HWL/Hardware Wrapper Layer plug‑in interface that real Pixel HWLs implement): `devices/EmulatedCamera/hwl/EmulatedCameraProviderHWLImpl.cpp` at `https://android.googlesource.com/platform/hardware/google/camera/+/refs/heads/main/devices/EmulatedCamera/hwl/EmulatedCameraProviderHWLImpl.cpp`. [Google](https://android.googlesource.com/platform/hardware/google/camera/+/refs/heads/main/devices/EmulatedCamera/hwl/EmulatedCameraProviderHWLImpl.cpp)

**Camera AIDL interface definitions** (every Pixel camera HAL must implement these, including Pixel 10):
- Provider: `https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/master/camera/provider/aidl/`
- Device: `.../camera/device/aidl/`
- Metadata (this is where `CameraCharacteristics` keys are defined in AIDL): `.../camera/metadata/aidl/`
- Common: `.../camera/common/aidl/` [Android Open Source Project](https://source.android.com/docs/core/camera/camera3)

**Pixel‑specific camera SE policy and integrations** are in `device/google/gs-common/camera/` and the per‑SoC sepolicy repo (e.g. `device/google/zuma-sepolicy/vendor/google_camera_app.te`, with commits like `https://android.googlesource.com/device/google/zuma-sepolicy/+/9c91ba1`). [Google](https://android.googlesource.com/device/google/zuma-sepolicy/+/9c91ba1) The equivalent for Pixel 10 (laguna‑sepolicy) is not public on android.googlesource.com.

**`CameraCharacteristics` / `CameraMetadata` entries that surface the LDAF, spectral/flicker sensor, and IMU fusion to apps:**
- `LENS_DISTORTION` and `LENS_INTRINSIC_CALIBRATION` — populated by the camera HAL using calibration data; combined with `SENSOR_INFO_PHYSICAL_SIZE`.
- `STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES`, `CONTROL_AWB_AVAILABLE_MODES` — affected by the spectral sensor (used as a hint for white balance).
- `CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`, `CONTROL_AE_ANTIBANDING_MODE` — affected by the flicker sensor.
- `LENS_FOCUS_DISTANCE`, `CONTROL_AF_REGIONS`, `CONTROL_AF_TRIGGER` — interact with the LDAF subsystem (the laser distance is one of several inputs into AF).
- `SENSOR_TIMESTAMP` and `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME` — required for IMU/camera fusion (so SLAM/EIS code can correlate gyroscope samples with camera frames).
- The keys themselves are defined in the AIDL metadata under `hardware/interfaces/camera/metadata/aidl/android/hardware/camera/metadata/CameraMetadataTag.aidl` and the constants are mirrored in `frameworks/av/camera/include/camera/CameraMetadata.h`.

### 2.3 LDAF (Laser Detect Auto Focus)

There is no separate "LDAF HAL" in AOSP — the LDAF is exposed to the camera HAL as one of several focus inputs and is driven over an I²C/SPI bus by a kernel driver. On Pixel devices the LDAF range data is read by the per‑sensor camera kernel driver (typically a STMicroelectronics VL53Lx ToF family part) and consumed inside the GCH for AF. Important paths:

- **Mainline Linux ToF/LDAF driver families** (the Pixel uses a derivative of these; the exact driver source for Pixel 10 lives inside the muzel kernel tree at `gitlab.com/grapheneos/kernel_pixel_muzel`, branch `16-qpr2`, [GrapheneOS](https://grapheneos.org/build) under `drivers/media/i2c/` and `drivers/iio/proximity/`):
  - `drivers/iio/proximity/vl53l0x-i2c.c` — STM VL53L0x ToF.
  - `drivers/iio/proximity/sx9324.c` and similar — capacitive proximity (different role).
- **Userspace consumption:** the GCH at `hardware/google/camera/common/hal/google_camera_hal/` reads LDAF distance estimates from the per‑device HWL implementation and feeds them into the AF controller. On older publicly‑mirrored Pixel HWLs, the LDAF is referenced via an internal sysfs node that the HWL polls; the GCH itself does not contain a stand‑alone "LDAF" file.
- **Thermometer app integration:** the FDA‑cleared body‑temperature flow uses LDAF distance via the same camera AF callbacks (reported by ZDNET/Gadget Hacks/Tom's Guide), gated to roughly 2 inches/5 cm before allowing a temperature sample. [Gadget Hacks](https://android.gadgethacks.com/news/google-removing-pixel-thermometer-feature-for-pixel-glow-leds/)

There is no public, isolated LDAF AIDL HAL in AOSP. The data path is: ToF kernel driver → camera HWL → GCH AF controller → AIDL `ICameraDevice` AF results.

### 2.4 Spectral and flicker sensor

Like LDAF, this is consumed inside the camera HAL rather than exposed as a standalone Android sensor. The hardware (an ams TCS3xxx‑class spectral/flicker sensor in past Pixels) is read by:

- **Kernel driver** (in the muzel/laguna tree, under `drivers/iio/light/` and `drivers/iio/light/tcs3xxx*.c`).
- **Camera HAL consumption:** white‑balance and anti‑banding decisions inside `common/hal/google_camera_hal/` use the spectral / flicker readings indirectly (via a sysfs node or a custom IIO consumer in the HWL), and surface the results via the standard Camera2 AE/AWB metadata keys listed above.

There is no AIDL "SpectralSensor" HAL in AOSP.

### 2.5 IR temperature sensor (rear bar)

The IR thermopile is exposed not through Camera HAL but through Android's Sensor framework and a Pixel‑specific Trusty/Fitbit pipeline:

- The sensor is enumerated through the AIDL Sensors HAL (`hardware/interfaces/sensors/aidl/`) using sensor type `TYPE_AMBIENT_TEMPERATURE` (33) or, more commonly on Pixel, a vendor private type for "object temperature".
- Kernel driver: a Melexis MLX90632/MLX90640‑class IR thermopile driver in `drivers/iio/temperature/` of the muzel kernel tree. Mainline Linux equivalents include `drivers/iio/temperature/mlx90632.c`.
- Userspace plumbing: the multi‑HAL Sensors service is enabled on Pixel via `device/google/zuma/device.mk` (and the equivalent in laguna) with `PRODUCT_PACKAGES += android.hardware.sensors-service.multihal`, [GitHub](https://github.com/GrapheneOS/device_google_zuma/blob/14/device.mk) and the sensors common bits are pulled in via `include device/google/gs-common/sensors/sensors.mk`. [GitHub](https://github.com/GrapheneOS/device_google_zuma/blob/14/device.mk) See `https://github.com/GrapheneOS/device_google_zuma/blob/14/device.mk`.
- The body‑temperature pipeline is in the Pixel Thermometer app (a Google‑proprietary APK) which uses both the IR sensor and the camera/LDAF; this is not in AOSP.
- The general (CPU/skin/battery) **Thermal HAL** for Pixel — used for thermal throttling and `Thermal` API queries — is at `https://android.googlesource.com/platform/hardware/google/pixel/+/refs/heads/master/thermal/` [Google](https://android.googlesource.com/platform/hardware/google/pixel/+/refs/heads/master/thermal/) (`service.cpp`, `thermal-helper.cpp`). [Google](https://android.googlesource.com/platform/hardware/google/pixel/+/refs/heads/master/thermal/service.cpp) [Google](https://android.googlesource.com/platform/hardware/google/pixel/+/refs/heads/master/thermal/thermal-helper.cpp) The Thermal HAL handles the SoC thermal zones, **not** the rear IR thermopile.

### 2.6 Ultrasonic fingerprint sensor

- **AIDL HAL definition (AOSP):** `hardware/interfaces/biometrics/fingerprint/aidl/` — defines `IFingerprint`, `ISession`, `SensorProps`, etc.
- **Pixel implementation:** the Pixel‑side fingerprint glue is in `device/google/gs-common/fingerprint/` (visible in the GrapheneOS mirror at `https://github.com/GrapheneOS/device_google_gs-common/tree/16/fingerprint`). The actual matcher and ultrasonic sensor driver are proprietary blobs from Qualcomm (3D Sonic) loaded via a `vendor.qti.hardware.fingerprint`/`vendor.qti.fingerprint` service plus a Trusty TA. Underneath, there is a kernel driver in the muzel tree (typically under `drivers/input/misc/` or a vendor‑specific `drivers/staging/` path) that handles the SPI/I²C transport to the sensor.
- **Framework side:** `frameworks/base/services/core/java/com/android/server/biometrics/sensors/fingerprint/` and `frameworks/base/core/java/android/hardware/fingerprint/`.
- The Pixel 10's specific ultrasonic model has not been confirmed by Google or by an FCC filing I could locate. Reporting (Biometric Update, Android Headlines) describes it as a "faster" successor to the Qualcomm 3D Sonic Gen 2 used in the Pixel 9. [GSMArena](https://www.gsmarena.com/google_pixel_10_series_upgraded_ultrasonic_fingerprint_scanner-news-68296.php)

### 2.7 UWB

UWB has a complete AOSP stack:

- **HAL interface (UCI):** `hardware/interfaces/uwb/` at `https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/master/uwb/` — based on the FiRa Consortium's UWB Command Interface. [Android Open Source Project](https://source.android.com/docs/core/connect/uwb)
- **Optional module `com.google.android.uwb`:** sources at `packages/modules/Uwb/` (framework, service, and `support_lib`). [Android Open Source Project](https://source.android.com/docs/core/connect/uwb)
- **Native UCI library (Rust):** `external/uwb/` [Android Open Source Project](https://source.android.com/docs/core/connect/uwb) at `https://android.googlesource.com/platform/external/uwb/`.
- **Framework system API:** `android.uwb.UwbManager` in `packages/modules/Uwb/framework/`. [Android Open Source Project](https://source.android.com/docs/core/connect/uwb)
- **Pixel‑specific HAL:** the legacy "Pixel‑specific UWB HAL" used in Android 12 was renamed to `hal_uwb_vendor` [XDA Developers](https://www.xda-developers.com/pixel-6-uwb-support-evidence/) (per the publicly visible commits found via 9to5Google and XDA reporting). The vendor HAL implementation is shipped as a closed binary on Pixel; the AOSP UCI HAL definition is the open part. UWB on Pixel 6 Pro through Pixel 10 Pro XL uses NXP UWB silicon [Pocket-lint](https://www.pocket-lint.com/android-phones-with-uwb-support/) (e.g. SR100/SR150 family) with a kernel driver in the muzel tree under `drivers/misc/` (vendor path).
- **AOSP architecture documentation:** `https://source.android.com/docs/core/connect/uwb`.

### 2.8 Ambient light and proximity sensors

Both are standard Android sensors:

- **AIDL HAL:** `hardware/interfaces/sensors/aidl/` (sensor types `TYPE_LIGHT` = 5, `TYPE_PROXIMITY` = 8).
- **Multi‑HAL service:** activated for Pixel via `PRODUCT_PACKAGES += android.hardware.sensors-service.multihal` and pulled in by `include device/google/gs-common/sensors/sensors.mk`. See `device/google/zuma/device.mk` (the laguna `device.mk` for Pixel 10 is not in public AOSP, but the inheritance pattern is identical).
- **Kernel drivers** (in the muzel kernel tree, mirrored at `gitlab.com/grapheneos/kernel_pixel_muzel`): under `drivers/iio/light/` (e.g. an ams‑OSRAM TMD3719 / TCS3xxx, the same family used on the Pixel 9 Pro), and a proximity driver either in `drivers/iio/proximity/` or `drivers/input/misc/`. The Pixel 10 Pro/Pro XL uses an under‑display proximity/ambient‑light sensor module behind a dedicated transparent window in the OLED panel (this is the same arrangement that has caused the recent flickering‑rectangle issue under the front camera reported by Android Police and Gadget Hacks in late 2025/early 2026, which Google has acknowledged via Pixel Community threads).

### 2.9 IMU (Accelerometer, Gyroscope, Magnetometer, Barometer)

- **AIDL HAL:** `hardware/interfaces/sensors/aidl/` (sensor types `TYPE_ACCELEROMETER` = 1, `TYPE_GYROSCOPE` = 4, `TYPE_MAGNETIC_FIELD` = 2, `TYPE_PRESSURE` = 6).
- **Pixel sensor service composition:** `device/google/gs-common/sensors/` (`sensors.mk`, the multi‑HAL config). Real Pixel 8/9/10 phones run sensor processing on the **CHRE (Context Hub Runtime Environment)** core inside the Tensor SoC (the "Always‑On Compute" / AOC subsystem). The CHRE bring‑up code on Pixel is in `device/google/gs-common/chre/` and `device/google/gs-common/aoc/`.
- **CHRE upstream:** `https://android.googlesource.com/platform/system/chre/`.
- **Kernel drivers (muzel kernel, `drivers/iio/`):**
  - `drivers/iio/imu/bmi3xx/` (Bosch BMI323/BMI270 — the Pixel 8/9 used Bosch BMI260/BMI323‑class parts; the Pixel 10 part hasn't been confirmed by an FCC filing I could find but is likely from the same family). Mainline reference: `drivers/iio/imu/bmi323/bmi323_core.c`, `bmi323_i2c.c`, `bmi323_spi.c`, [Code Browser](https://codebrowser.dev/linux/linux/drivers/iio/imu/bmi323/bmi323_spi.c.html) `bmi323.h`. [Openwall](https://lists.openwall.net/linux-kernel/2024/07/25/12)
  - `drivers/iio/magnetometer/` for the magnetometer (commonly an AKM AK0991x).
  - `drivers/iio/pressure/` for the barometer (commonly a Bosch BMP3xx).

**Sensor fusion code that combines IMU with camera:**
- The user‑space IMU/camera fusion for EIS (Electronic Image Stabilization), gyro‑based rolling‑shutter correction, and Fused Video Stabilization runs inside Google's Camera HAL — `hardware/google/camera/common/hal/google_camera_hal/` — and inside the closed‑source Pixel HWL plus Tensor‑G5 ISP firmware. The relevant Camera2 metadata keys are `SENSOR_TIMESTAMP` with `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (so frame timestamps share a clock with `Sensor.timestamp`), `LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`, and `STATISTICS_OIS_*`.
- The kernel‑level synchronization between camera frame strobes and IMU timestamps is handled by `drivers/staging/android/sync/` + per‑SoC fence drivers in the muzel kernel.

### 2.10 Bluetooth 6 and Wi‑Fi 7

These are not sensors, but for completeness:

- **Bluetooth HAL (AIDL):** `hardware/interfaces/bluetooth/` — Pixel uses Broadcom silicon with the `bcmbt` integration in `device/google/gs-common/bcmbt/` and `device/google/gs-common/bluetooth/`. Bluetooth Core 6.0 features (channel sounding, etc.) are exposed via `frameworks/base/core/java/android/bluetooth/`.
- **Wi‑Fi HAL (AIDL):** `hardware/interfaces/wifi/aidl/`. Pixel Wi‑Fi 7 driver glue lives in `device/google/gs-common/wlan/`.

### 2.11 Satellite SOS

- Pixel 10's satellite SOS is implemented at the **Telephony HAL / RIL level**, not as a separate sensor. Skylo's NB‑NTN (3GPP Rel‑17) [IoT Business News](https://iotbusinessnews.com/2025/08/21/20265-google-skylo-bring-satellite-connectivity-to-pixel-10-and-launch-pixel-watch-4-with-2-way-emergency-messaging/) is exposed through the modem's RIL interface and consumed by the Telephony framework. AOSP additions for satellite live in `frameworks/opt/telephony/src/java/com/android/internal/telephony/satellite/` and the AIDL `android.telephony.satellite` API.
- The Pixel‑specific RIL layer is `device/google/gs-common/pixel_ril/` and `device/google/gs-common/gril/`.

### 2.12 The (absent) Soli radar

There is no Soli code path in AOSP for the Pixel 10 because there is no Soli hardware. For historical reference, Soli's "Motion Sense" code on the Pixel 4 lived under `vendor/google/coral/` (closed) plus a small "Aware" service in the framework; [Google Support](https://support.google.com/pixelphone/answer/7157629?hl=en) all of that was removed when Soli was discontinued after the Pixel 4. The user's claim that the Pixel 10 Pro XL has no Soli/Project Radar chip is correct.

---

## 3. Practical Pointers (Summary Table)

| Sensor / Subsystem | Public AOSP code path | Pixel‑specific code path | Kernel driver area (muzel/laguna tree) |
|---|---|---|---|
| Accelerometer / Gyro / Magnetometer / Barometer | `hardware/interfaces/sensors/aidl/`; `system/chre/` | `device/google/gs-common/sensors/`, `device/google/gs-common/chre/`, `device/google/gs-common/aoc/` | `drivers/iio/imu/bmi*`, `drivers/iio/magnetometer/`, `drivers/iio/pressure/` |
| Proximity / Ambient light | `hardware/interfaces/sensors/aidl/` | `device/google/gs-common/sensors/` | `drivers/iio/light/`, `drivers/iio/proximity/` |
| IR temperature sensor (rear bar) | `hardware/interfaces/sensors/aidl/` (vendor type) | (Thermometer app is closed‑source); `hardware/google/pixel/thermal/` for SoC thermal only | `drivers/iio/temperature/` (MLX90632/MLX90640‑class) |
| Ultrasonic fingerprint | `hardware/interfaces/biometrics/fingerprint/aidl/` | `device/google/gs-common/fingerprint/` (closed Qualcomm + Trusty matcher) | vendor SPI driver in `drivers/input/misc/` |
| LDAF | None as a standalone HAL — exposed via Camera2 AF results | Inside `hardware/google/camera/common/hal/google_camera_hal/` (HWL reads LDAF) | `drivers/iio/proximity/` (VL53Lx‑class ToF) |
| Spectral / flicker sensor | None as a standalone HAL — used inside Camera HAL for AE/AWB | Inside `hardware/google/camera/common/hal/google_camera_hal/` | `drivers/iio/light/tcs3xxx*` |
| Camera (rear 50 MP GNV / 48 MP IMX858 ×2 / front 42 MP IMX858) | `hardware/interfaces/camera/{provider,device,metadata,common}/aidl/`; `hardware/google/camera/common/hal/aidl_service/`; [Android Open Source Project](https://source.android.com/docs/core/camera/camera3) `hardware/google/camera/common/hal/google_camera_hal/` | Pixel‑specific HWL (closed); `device/google/gs-common/camera/` for the build glue | `drivers/media/i2c/` for image‑sensor drivers |
| UWB | `hardware/interfaces/uwb/`; `packages/modules/Uwb/`; `external/uwb/` [Android Open Source Project](https://source.android.com/docs/core/connect/uwb) | `hal_uwb_vendor` (closed binary); integration in `device/google/gs-common/` | NXP SR1xx driver (vendor staging) |
| Bluetooth v6 | `hardware/interfaces/bluetooth/` | `device/google/gs-common/bcmbt/`, `device/google/gs-common/bluetooth/` | Broadcom BT driver |
| Wi‑Fi 7 | `hardware/interfaces/wifi/aidl/` | `device/google/gs-common/wlan/` | Broadcom Wi‑Fi 7 driver |
| Satellite SOS (NB‑NTN) | Telephony AIDL + `frameworks/opt/telephony/.../satellite/` | `device/google/gs-common/pixel_ril/`, `device/google/gs-common/gril/`, `device/google/gs-common/modem/` | Modem firmware (closed; reportedly MediaTek T900) |
| Thermal HAL (SoC throttling, NOT the IR bar) | `hardware/interfaces/thermal/aidl/` | `https://android.googlesource.com/platform/hardware/google/pixel/+/refs/heads/master/thermal/` (`service.cpp`, `thermal-helper.cpp`) | `drivers/thermal/` |
| Soli / Project Radar | — (absent on Pixel 10) | — | — |

---

## 4. Honest Caveats and Limitations

1. **No public Pixel 10 device tree.** Google removed Pixel device trees from AOSP starting with Android 16. [XDA Forums](https://xdaforums.com/t/build-aosp-gsi-from-source-and-flash-it-with-dsu.4749904/) The most authoritative public mirror of the Pixel 10‑family **kernel** (which contains all kernel sensor drivers) is the GrapheneOS mirror at `gitlab.com/grapheneos/kernel_pixel_muzel.git` (branch `16-qpr2`) and the prebuilt at `github.com/GrapheneOS/device_google_laguna-kernels_6.6`. The userspace HAL implementations specific to the Pixel 10 (camera HWL, fingerprint matcher, UWB vendor HAL, modem RIL extensions) are shipped only as closed binaries in Google's vendor partition. The shared, open parts that Pixel 10 uses are in `hardware/google/pixel`, `hardware/google/camera`, `device/google/gs-common`, and `system/chre`, all on android.googlesource.com.
2. **The Pixel 10's exact sensor part numbers (IMU, magnetometer, fingerprint, proximity, IR thermopile) have not been confirmed by an FCC filing or formal teardown report I could find.** They are very likely the same families as on the Pixel 9 Pro XL (e.g. Bosch BMI323, AKM AK0991x, Bosch BMP390, Qualcomm 3D Sonic Gen 2 ultrasonic, Melexis MLX90632 IR), but treat those specific part numbers as inference, not verified fact.
3. **The user's "FDA‑cleared" claim** is true at the level of the Thermometer **app** (Fitbit/Google received De Novo classification from the FDA; reported by Fierce Biotech, Tom's Guide, ZDNET, Gadget Hacks). The hardware itself is not "FDA‑cleared" as a medical device; it is the on‑device algorithm + sensor combination that received clearance. There are also recent (late‑2025) leaks reported by Gadget Hacks and ZDNET indicating Google may drop the IR temperature sensor from the Pixel 11 Pro in favor of a "Pixel Glow" RGB LED system, [Gadget Hacks](https://android.gadgethacks.com/news/google-removing-pixel-thermometer-feature-for-pixel-glow-leds/) but that is rumor/leak and does not affect the Pixel 10 Pro XL.
4. **Bluetooth, satellite SOS and Wi‑Fi figures** on the Google spec page are marketing claims (e.g. "Bluetooth v6 with antenna diversity"); the actual Bluetooth Core 6.0 feature subset that ships and is enabled on day‑one is not enumerated by Google.
5. **Reports of which modem is in the Tensor G5** (MediaTek T900) come from press leaks (Android Authority, FoneArena) and have not been formally confirmed by Google.

In sum: the user's sensor list is accurate as a description of the Pixel 10 Pro XL's hardware, with the small caveats listed above. The publicly available source code that interfaces with each of these sensors is concentrated in five places — `hardware/interfaces/...` (AIDL definitions on android.googlesource.com), `hardware/google/pixel`, `hardware/google/camera`, `device/google/gs-common`, and the muzel/laguna kernel tree mirrored by GrapheneOS — with the per‑model Pixel 10 device tree itself remaining closed.