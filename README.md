# Fine volume control for Android TV

This app provides fine-grained global audio gain control on a Homatics Dune HD
Box R 4K Plus. It applies a gain inside the Amlogic audio HAL and uses the
remote's volume buttons to adjust it in small steps.

It has two parts: an Android app holding the audio effect and the on-screen
panel, and `finevold`, a small daemon that captures the volume keys before
Android can act on them. The daemon ships inside the APK and the app starts it
by itself, by speaking the adb protocol to the box's own adbd.

The implementation is designed for a stock, non-rooted device. It does not
require Android Studio or the NDK; the project can be built and installed with
the included Gradle wrapper, the Go toolchain, and the Makefile.

## Current status

As of 2026-10-31, volume keys are captured by a `shell`-uid daemon that grabs
the input devices outright. The broadcast, `MediaSession`, and
accessibility-key input paths were all removed: they were unreliable, lagged
behind the keypress, and forced the app to revert the system volume change it
had already caused.

Verified on the target box:

- `EVIOCGRAB` from the `shell` uid takes the volume keys away from Android
  completely. The native OSD never shows and `STREAM_MUSIC` never moves.
- Every non-volume key is replayed through a `uinput` twin device, so the
  remote behaves normally while its node is grabbed.
- Volume up, volume down, mute, and held-key repeat all reach the app over a
  loopback socket.
- The app starts the daemon itself over adb, both at boot and after a crash.
  Killing the daemon with `SIGKILL` gets it restarted within a minute.
- Volume is a gain inside the audio HAL, set through
  `AudioManager.setParameters("SOURCE_GAIN=...")`. It attenuates the tunneled
  output that Netflix uses, which no audio effect can reach.
- 50 levels and a maximum attenuation of -50 dB by default. Level 0 is mute.
  Mute, long-press ramping and ~1 dB steps were all confirmed on Netflix with
  no audible clicks.

### The vendor volume curve

The reason this app exists. `/vendor/etc/audio_policy_engine_*.xml` replaces
the AOSP media curve with `CUSTOM_VOLUME_CURVE`, tagged `SEI904SAP-66` and
sitting next to a curve commented "for SOUNDBAR". SEI Robotics ships one
firmware for this box, the Nokia 8010, the RockTek G2, Dune, and soundbar
products. Sampled at this box's 25 `STREAM_MUSIC` indices it gives:

| index | 1 | 2 | 3 | 4 | 5 | 6 | ... | 25 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| dB | -49.25 | -28.75 | -17.25 | -14.87 | -12.50 | -11.75 | | 0.00 |
| step | | +20.50 | +11.50 | +2.38 | +2.37 | +0.75 | ~0.6 | |

Nineteen fine steps of 0.5 to 0.88 dB are crammed into the top 11.75 dB, and
below that the curve falls off a cliff. That is sensible for a soundbar, where
the digital path stays near 0 dBFS and an analog amp attenuates, but wrong for
a box feeding an external DAC, where the box is the volume control. Normal
listening on the DAC1 route lands at index 3, right on the cliff edge, where
one press up is +2.4 dB and one press down is -11.5 dB.

The curve cannot be fixed on the device: `/vendor` is covered by dm-verity
under a locked bootloader (`ro.boot.flash.locked=1`,
`vbmeta.device_state=locked`, `veritymode=enforcing`), and this hardware has
no public root. Editing it would simply stop the box from booting.

### Why an audio effect is not enough

Netflix plays through a tunneled output:

```
AudioStreamOut flags 0x41 (AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_HW_AV_SYNC)
```

`dumpsys media.audio_flinger` shows that thread carrying the only active
track and reporting `0 Effect Chains`, while the mixer thread that does hold
the session-0 effect has no active tracks. Effects attach to mixer threads, so
anything tunneled bypasses them completely. An earlier version of this app
used a `DynamicsProcessing` effect and pinned the system volume to maximum,
expecting the effect to supply the attenuation; on Netflix that produced
full-scale output instead.

Everything else within reach was ruled out too:

| lever | why not |
| --- | --- |
| `DynamicsProcessing`, session 0 | mixer threads only, no effect chain on tunneled output |
| ALSA mixer, `tinymix` | `/dev/snd/*` is `system:audio` 0660 with SELinux `audio_device`; `shell` is not in `audio` |
| USB Audio Class volume in the DAC | interface-directed requests need the interface claimed, detaching `snd-usb-audio` |
| `STREAM_VOICE_CALL` aliased at 0..100 | a rescaled view of the same 25-step value: one keypress moves it 20 → 24 → 28 |
| more stream steps | `MAX_STREAM_VOLUME` is a framework constant |
| analog gain staging | fixes granularity by sacrificing range, which is the point of having range |

### The HAL gain

The Amlogic HAL accepts gains through `setParameters()`, which
`AudioManager` exposes publicly and which only needs `MODIFY_AUDIO_SETTINGS`:

```
SOURCE_GAIN=<atv> <dtv> <hdmiin> <av> <media>    # dB, five floats
POST_GAIN=<lineout> <spdif> <speaker>            # dB, not routed to USB
```

Reading it back with `getParameters("SOURCE_GAIN")` confirms the values land:
`-0.5` becomes `-0.499999`, `-6.25` becomes `-6.249987`. The range runs to
`-100 dB`, where it clamps, and positive values are accepted but unused here
since they amplify. Because the gain lives in the HAL it applies to the
tunneled path, to the mixer path, and across app switches.

The Android package ID was changed from `org.example.tvvolume` to
`cc.jarry.finevolume`; the old app was uninstalled. Android treats this as a
separate app, so preferences and accessibility-service enablement were reset.

## Device and environment

- Device: Homatics Dune HD Box R 4K Plus
- Android: 14, runtime API 34
- App: `cc.jarry.finevolume`, version 0.1
- Compile SDK: 35; target SDK: 34; minimum SDK: 28
- ADB serial used during testing: `192.168.1.29:39443`
- Build tools: JDK 21 and Android SDK command-line tools

The Makefile defaults to:

- `ANDROID_HOME=$HOME/.local/share/android/sdk`
- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk`

Override these variables if the local paths differ.

## Build and install

Build the debug APK and the daemon:

```sh
make
```

Install the app on the default ADB device:

```sh
make install
```

Build, install, and open the app on the tested device:

```sh
make run ADB_SERIAL=192.168.1.29:39443
```

Push and start the daemon, replacing any running instance:

```sh
make daemon-start
```

Other Make targets are `make daemon-stop`, `make daemon-log`, and
`make clean`. The resulting debug APK is
`app/build/outputs/apk/debug/app-debug.apk`.

## Setup and use

1. Install and open **Fine volume control**.
2. Optionally choose the maximum attenuation and number of levels using the
   two configuration buttons. Defaults are -50 dB and 50 levels; attenuation
   choices cycle through -20, -30, -40, and -50 dB, and level counts toggle
   between 50 and 100.
3. Open the app's **Open accessibility settings** button and enable the Fine
   volume control accessibility service. It can also be enabled over adb:

   ```sh
   cur=$(adb shell settings get secure enabled_accessibility_services)
   adb shell settings put secure enabled_accessibility_services \
       "$cur:cc.jarry.finevolume/cc.jarry.finevolume.VolumeAccessibilityService"
   ```

4. Accept the "Allow debugging" prompt that appears on the TV shortly after.
   Tick "always allow"; this authorizes the app's own key, not the laptop's.
   The app then starts the daemon and shows `Daemon: connected`.
5. Use the remote's volume up/down buttons to move the displayed level between
   0 (mute) and the configured maximum level. The mute key toggles level 0.

`make daemon-start` still exists for starting the daemon from a laptop, which
is useful when iterating on the daemon itself.

The app's screen shows whether the accessibility service is enabled, whether
the daemon is connected, the current level, maximum attenuation, and number of
levels. These settings are stored in the `volume` shared preferences. Changing
either configuration while the service is running resets the level to the
midpoint.

### After a reboot

Nothing to do. The accessibility service starts with the system, finds
nothing listening, and starts the daemon over adb. Until that happens the
volume keys keep working natively, on the system's own coarse curve.

## How it works

### finevold, the key-capture daemon

An app cannot read `/dev/input`: its uid is not in the `input` group and
SELinux denies `untrusted_app` access to `input_device`. The `shell` uid is in
both the `input` and `uhid` groups, so the daemon has to run from adb. It is a
static `linux/arm` Go binary, built without the NDK.

For every input node that advertises `KEY_VOLUMEUP` or `KEY_VOLUMEDOWN`, the
daemon:

1. Creates a `uinput` twin that copies the node's name, bus, vendor, product,
   version, and capability bits. Android therefore resolves the same key
   layout and `.idc` files for the twin as for the original device.
2. Takes the original node with `ioctl(EVIOCGRAB)`, which makes the kernel
   deliver its events to the daemon alone. Android's `InputReader` stops
   seeing the device entirely.
3. Replays every event report through the twin, except the reports whose only
   key events are volume up, volume down, or mute. Those are sent to the app
   as `up`, `down`, or `mute` lines on `127.0.0.1:38501`.

Because the key never reaches Android, there is no native OSD, no stream
volume change, and nothing for the app to revert.

Volume keys are only consumed while a client is connected. With no client, the
daemon replays them to the twin unchanged, so the box keeps its normal volume
behaviour whenever the app is not running.

Android disables kernel autorepeat on the devices it opens, so a held volume
key reports a single press. The daemon synthesizes repeats itself after
`-repeat-delay`, every `-repeat-interval`. If a device does emit kernel
repeats, they are used instead.

The twins advertise volume keys too, so they are tracked by the node reported
by `UI_GET_SYSNAME` and skipped by the device scan; otherwise each scan would
grab and mirror the previous twin without end. Devices are rescanned every
second, which re-grabs the Bluetooth remote after it reconnects.

### Starting the daemon without a host

An app cannot reach the `shell` uid, and a child process it spawns inherits
its own uid, so the app cannot grab anything itself. What it can do is talk
adb to `127.0.0.1:5555`, which is how `AdbClient` starts the daemon:

1. It generates an RSA 2048 key pair once, in the app's private storage.
2. It performs the `CNXN` / `AUTH` handshake. The token is signed with
   PKCS#1 v1.5 over a SHA-1 `DigestInfo` prefix, which is what
   `RSA_verify(NID_sha1, ...)` in adbd expects, and the public key is sent in
   adb's own Montgomery encoding rather than any standard format.
3. The first attempt raises the on-screen authorization prompt. Accepting it
   with "always allow" stores the key in `/data/misc/adb/adb_keys`, and every
   later connection is silent.
4. It opens `shell:` and runs a command that copies the daemon out of the
   APK's native library directory, installs it in `/data/local/tmp`, and
   starts it detached with `setsid`.

The command stays alive for two seconds after spawning, because adbd
otherwise tears the daemon down together with the shell that started it. It
then reports whether the daemon is actually running, so a failure shows up in
the app instead of being silently assumed to have worked.

This runs whenever the action socket cannot be reached, at most once a
minute. Accessibility services are started by the system at boot, so the same
path covers both a reboot and a daemon that died. This needs network adb to
stay enabled on the box; it is the setting that survives reboots here.

### Audio processing

`VolumeAccessibilityService` keeps a socket open to the daemon, reconnecting
every 2 seconds whenever it is absent, and applies each action on the main
thread.

For level `L` in the range 1 through `N`, with configured maximum attenuation
`A` dB, the target is `-A * (N - L) / (N - 1)` dB, so level 1 is `-A` and
level `N` is full scale. Level 0 mutes by asking for -100 dB. At the defaults
that is 50 levels of about 1.02 dB.

The target is then split between the system volume index and the HAL gain.
The index takes as much as its coarse curve allows, picking the quietest index
that is still louder than the target, and the HAL gain attenuates the
remainder. The index only moves when the gain would otherwise drift more than
6 dB away from it, so most keypresses are a single HAL write.

The point of that split is power cuts. The HAL gain is lost when the box loses
power while the system volume index is not, so the index carries as much of
the attenuation as it usefully can.

The index is never taken below the point where the curve stops being smooth,
found by walking down from the maximum until a step exceeds 3 dB. On this box
that stops at index 3, just above the 11.5 dB cliff, and everything quieter is
left entirely to the gain. Crossing that cliff was audible whichever way the
two writes were ordered: the gain has to rise by 10.5 dB to compensate for the
index dropping 11.5 dB, and a HAL parameter takes effect sooner than a stream
volume change, so the rise was heard before the drop it compensated for.

For the index steps that remain, up to 2.4 dB, the rule is that whichever
change attenuates is issued first. Raising the gain is the one case ordering
cannot fix, so when the index drops the gain follows 60 ms later. Anything
left over is then a brief dip rather than a bump.

A side effect of the floor is that a power cut leaves the box at index 3,
about -17 dB, whatever the level was.

A watchdog re-reads `SOURCE_GAIN` every 5 seconds and restores it if the HAL
has forgotten it, for instance after reopening an output. It only ever
rewrites the gain, never the index, so it cannot click.

Android's own key repeat does not ramp the volume on this box: a long press
produces exactly one `KEY_VOLUMEDOWN` down/up pair, the remote does not repeat
at the HID level, and `EventHub` disables kernel autorepeat on every device it
opens. The smooth ramp comes from the daemon synthesizing repeats itself.

### Gain panel

The panel is an `AccessibilityService` overlay (`TYPE_ACCESSIBILITY_OVERLAY`),
so it can appear above other apps without taking focus or touch input. Since
the daemon consumes the volume keys, the system never shows its own dialog and
the two can never appear at once.

It is drawn to be indistinguishable from the TV volume dialog of SystemUI,
using that dialog's own resources rather than guesswork:

| element | value | source |
| --- | --- | --- |
| panel | 48 x 274 dp, fully rounded | `volume_dialog_panel_width`, and 6 + 36 + 190 + 36 + 6 |
| offset from the right | 24 dp | `volume_dialog_panel_transparent_padding` |
| bubbles | 36 dp circles | `tv_volume_dialog_bubble_size` |
| padding above and below | 6 dp | `tv_volume_dialog_row_padding` |
| slider area | 190 dp | `volume_dialog_panel_height` |
| track | 4 dp wide, rounded | `volume_dialog_slider_width` |
| number | 16 sp, tabular figures | `tv_volume_number_text_size` |
| icon | 20 dp | `tv_volume_icons_size` |
| panel colour | `#E61F232B` | `volume_dialog_background_color` |
| bubble colour | `#08FFFFFF` | `tv_volume_dialog_circle` |
| track colour | `#A03C4043` | `tv_volume_dialog_seek_bar_background` |
| accent | `#FFDADCE0` | `tv_volume_dialog_accent` |
| show | 300 ms, alpha and slide from half width | `config_dialogShowAnimationDurationMs`, `LogDecelerateInterpolator` |
| hide | 250 ms, same in reverse | `config_dialogHideAnimationDurationMs`, `LogAccelerateInterpolator` |
| timeout | 3000 ms | `VolumeDialogImpl.DIALOG_TIMEOUT_MILLIS` |

Two details were taken from the device instead, where it disagrees with the
AOSP branch above: the thumb is 16 dp rather than 24 dp, and the filled part
of the track is drawn in the accent colour rather than
`tv_volume_dialog_seek_bar_fill`. The track is inset at both ends by the thumb
radius plus the 8 dp focus ring, and the thumb centre travels the whole track,
which is what `AbsSeekBar` does. All of this was checked by screenshotting the
real dialog and comparing pixel positions.

Like the system dialog, the bubble shows only the level, with no maximum.

## Rejected input paths

These were all implemented and removed; the history is kept so they are not
retried.

- **`android.media.VOLUME_CHANGED_ACTION` broadcast.** Delivered only after
  Android has already changed the stream volume and drawn its OSD. The app had
  to revert the change it observed, which flashed the native panel, fought the
  system's own volume handling, and lagged visibly behind the keypress.
- **Accessibility `onKeyEvent()`.** The service holds
  `FLAG_REQUEST_FILTER_KEY_EVENTS` and reports key-filter capability
  (`capabilities=8`), but this firmware never delivers physical volume presses
  to the callback. Disabling the installed TV Remote Button Mapper
  (`com.rco.buttonmapper`), whose description says it pauses itself on volume
  keys, made no difference.
- **`MediaSession` with a remote `VolumeProvider`.** Volume keys only route to
  a session that is the active one, which the real player owns exactly when
  volume control is wanted. It also risks showing a media control and
  competing with that player's session, and Android 14 does not forward
  remote-session mute adjustments to `VolumeProvider`.
- **A `DynamicsProcessing` effect on session 0.** Works for apps that mix,
  silently does nothing for Netflix, which is tunneled. Combined with pinning
  the system volume to maximum it produced full-scale output.
- **A constant DSP offset to reposition the listening point.** Same flaw: it
  never reached the tunneled path, so it only made non-tunneled apps quieter
  while leaving Netflix 12 dB louder.

Root, firmware changes, and system privileges remain out of scope; the daemon
needs nothing beyond the `shell` uid that adb already provides.

## Source layout

- `daemon/main.go` — device scan, grab lifecycle, event filtering, key repeat.
- `daemon/evdev.go` — evdev and uinput ioctls, twin device creation.
- `daemon/hub.go` — loopback action stream and client tracking.
- `app/src/main/java/cc/jarry/finevolume/MainActivity.java` — status, service
  settings shortcut, level display, attenuation and level-count controls.
- `app/src/main/java/cc/jarry/finevolume/VolumeAccessibilityService.java` —
  HAL gain, index and gain split, daemon client and supervisor, and the
  animated gain panel.
- `app/src/main/java/cc/jarry/finevolume/AdbClient.java` — just enough of the
  adb protocol to run one shell command against the local adbd.
- `app/src/main/res/xml/volume_accessibility_service.xml` — accessibility
  service declaration, used for the overlay window.
- `app/src/main/AndroidManifest.xml` — app and service declarations, with the
  `MODIFY_AUDIO_SETTINGS` and `INTERNET` permissions.
- `Makefile` — app and daemon build, install, run, and clean targets.

## Known caveats

- Everything depends on network adb staying enabled on the box. If it is
  turned off, the app can no longer start the daemon and the box falls back
  to the system's own coarse volume.
- The daemon cannot run inside the app process: the app uid lacks the `input`
  group and SELinux access to `input_device`, so a child process it spawns
  cannot grab anything. Only adb, Shizuku, or root provide that uid.
- The HAL gain is applied by the app, so between a power cut and the service
  starting, loudness is whatever the system volume index alone gives. That is
  within a couple of dB of the intended level while the index is free to
  track it, and at most about -17 dB once the level drops below the floor.
- The app is authoritative: it restores its stored level on startup and does
  not watch the system volume. Anything else that moves the index, during the
  window after boot before the daemon starts for instance, is overridden at
  the next level change.
- `SOURCE_GAIN` is an Amlogic HAL parameter, not an Android API. Nothing
  guarantees it survives a firmware update.
- The DSP, the -12 dB offset, the volume-pinning and the broadcast and
  MediaSession input paths were all removed. Their history is kept below so
  they are not attempted again.
- While a device is grabbed, every one of its keys passes through the daemon.
  If the daemon stops, the kernel releases the grab and the original device
  takes over again, so a crash cannot leave the remote dead.
- Everything here was established on this box, against this firmware, with
  the USB DAC as the output. The volume curve, the HAL parameter and the
  input device layout are all vendor specific.

## License

MIT, see [LICENSE](LICENSE). Every source file carries an SPDX identifier.
