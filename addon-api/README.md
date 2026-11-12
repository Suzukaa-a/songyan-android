# Songyan addon API

The cross-process contract between the Songyan keyboard (松烟) and an external addon APK. Licensed
Apache-2.0 so that addons can use any license; every source file carries an SPDX
header. The keyboard itself stays GPL-3.0-only.

An addon is an ordinary app that the user installs from the system. Songyan binds to its service and
never downloads, installs or loads the addon's code. An addon can:

- run text tools (the keyboard previews the result, then writes it back itself),
- run its own voice input (it records and recognizes; the keyboard shows the state and commits the
  text), and
- draw its own panel inside the keyboard window.

It cannot see candidates, raw key events, clipboard history or learning data, cannot learn the host
app's package name and cannot switch input schemes. Text reaches the addon only when the user taps
one of its tools or its panel asks for it.

## Using it

```kotlin
dependencies {
    implementation(project(":addon-api"))
}
```

Declare the service in the addon's manifest, exported so the keyboard can bind:

```xml
<service android:name=".MyAddonService" android:exported="true">
    <intent-filter>
        <action android:name="app.songyan.ime.addon.SERVICE" />
    </intent-filter>
</service>
```

Implement `SongyanAddon` and one `SongyanAddonSession`, and return them from a subclass of
`SongyanAddonService`. `SongyanAddonService` verifies the caller, negotiates the protocol version
and turns exceptions into failure callbacks, so the addon's own code only deals with its features.
`addon-sample` in this repository is the complete template.

## Protocol version

- `AddonProtocol.VERSION` is 1. The host sends the range it supports in `AddonHostInfo`; the addon
  answers with the highest common version in `AddonHandshake`. With no common version the host shows
  the addon as incompatible and runs nothing (`AddonHandshake.protocolVersion == 0`).
- The protocol is append-only. Binder methods may be added at the end, never removed, renamed or
  reordered; payloads are Bundles, so a new optional key never changes an interface. A change to the
  frozen method table in `AddonInterfaceStabilityTest` is a protocol version bump.
- Unknown keys and unknown capability names are ignored, so a newer peer never breaks an older one.

## Interfaces (protocol 1)

| Interface | Implemented by | Methods (frozen order) |
| --- | --- | --- |
| `ISongyanAddonService` | addon | `negotiate(Bundle)`, `openSession(Bundle, ISongyanAddonHost)`, `disconnect()` |
| `ISongyanAddonSession` | addon | `tools()`, `runTextTool(requestId, toolId, Bundle)`, `cancelRun`, `startVoice`, `stopVoice`, `openPanel(requestId, Bundle, ISongyanAddonPanelHost)`, `updatePanelContext`, `closePanel`, `close` |
| `ISongyanAddonHost` | host, oneway | `onToolResults`, `onToolFailed`, `onVoiceText`, `onVoiceState`, `onWarning` |
| `ISongyanAddonPanelHost` | host | `onSurfaceReady(SurfacePackage, w, h)`, `insertText(Bundle)`, `readText(Bundle)`, `panelContext()`, `requestPanelSize(w, h)`, `closePanel()` |

Typed payloads live in `AddonModel.kt` (`AddonHostInfo`, `AddonHandshake`, `AddonToolDescriptor`,
`AddonPanelContext`, `AddonTextQuery`/`AddonTextResult`, `AddonTextWrite`/`AddonWriteResult`,
`AddonFailure`, `AddonVoiceState`). The host-side connector is `AddonHostClient`.

## Own panel

1. The host calls `openPanel` with an `AddonPanelContext`: `hostWindowToken` (a
   `SurfaceView.getHostToken()` value), display id, available width/height in pixels, insets, the
   editor's `inputType`/`imeOptions`, dark mode and the keyboard's primary color.
2. The addon creates `SurfaceControlViewHost(context, display, hostWindowToken)` **in its own
   process**, calls `setView` and passes `surfacePackage` back through
   `AddonPanelHost.onSurfaceReady`.
3. The host attaches the package to a `SurfaceView` with `setChildSurfacePackage`. The host's
   SurfaceView must call `setZOrderOnTop(true)` before the package is attached; otherwise the
   embedded hierarchy renders but receives no touches (measured on API 36).
4. The panel may `insertText` (optionally deleting before the cursor), read the selection or the
   text before the cursor, ask for more height, close itself, or use the context it was given. All
   editor operations return typed results; nothing throws across the boundary.

## Caller verification

The addon service is exported, so it must verify who is binding. `AddonCallerPolicy` requires the
caller UID's package to be one of Songyan's packages **and** its signing certificate SHA-256 to be in
the addon's own pinned list. Fill the fingerprint in when publishing; the empty default rejects
everyone. A debuggable addon may explicitly accept unverified callers — the repository's sample uses
that only so its integration tests and device probes can bind without Songyan's release key.
