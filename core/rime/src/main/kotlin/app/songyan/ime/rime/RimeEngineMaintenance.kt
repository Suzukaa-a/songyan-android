package app.songyan.ime.rime

import java.io.File

// Bodies of the RimeEngine maintenance and learning-backup operations. Each runs on the engine
// thread (the caller wraps it in RimeDispatcher.onEngine) and is deployment-process only.

internal fun RimeEngine.syncUserDataOnEngine(): RimeSnapshot {
    requireMaintainable("sync")
    val wanted = readCurrentSchemaId()
    val options = capturedOptions()
    releaseSession()
    val started = RimeNative.nativeSyncUserData(engine)
    RimeNative.nativeJoinMaintenance(engine)
    restoreSessionAfterMaintenance(wanted, options)
    if (!started) {
        throw RimeException(RimeException.Kind.OPERATION_FAILED, "cannot start user-data sync")
    }
    if (deployState() == RimeDeployState.FAILURE) {
        throw RimeException(RimeException.Kind.OPERATION_FAILED, "sync reported a deployment failure")
    }
    return actionSnapshot()
}

internal fun RimeEngine.redeployOnEngine(fullCheck: Boolean): RimeSnapshot {
    requireMaintainable("redeploy")
    val wanted = readCurrentSchemaId()
    val options = capturedOptions()
    releaseSession()
    val started = RimeNative.nativeStartMaintenance(engine, fullCheck)
    RimeNative.nativeJoinMaintenance(engine)
    restoreSessionAfterMaintenance(wanted, options)
    if (!started && fullCheck) {
        throw RimeException(RimeException.Kind.OPERATION_FAILED, "cannot start maintenance")
    }
    if (deployState() == RimeDeployState.FAILURE) {
        throw RimeException(RimeException.Kind.OPERATION_FAILED, "deployment reported failure")
    }
    return actionSnapshot()
}

internal fun RimeEngine.deploySchemaFileOnEngine(file: File): Boolean {
    requireMaintainable("schema compilation")
    val wanted = readCurrentSchemaId()
    val options = capturedOptions()
    // librime compiles with no session open, as for a maintenance pass.
    releaseSession()
    val compiled = RimeNative.nativeDeploySchema(file.absolutePath)
    restoreSessionAfterMaintenance(wanted, options)
    return compiled
}

internal fun RimeEngine.sourceProjectionJsonOnEngine(schemaId: String): String? {
    requireMaintainable("source projection")
    if (!canAct()) return null
    if (!RimeNative.nativeSelectSchema(engine, session, schemaId)) return null
    if (readCurrentSchemaId() != schemaId) return null
    val wasAscii = RimeNative.nativeGetOption(engine, session, RimeEngine.ASCII_MODE)
    RimeNative.nativeSetOption(engine, session, RimeEngine.ASCII_MODE, false)
    return try {
        RimeNative.nativeSyllableDisplayMapJson(session)
    } finally {
        RimeNative.nativeSetOption(engine, session, RimeEngine.ASCII_MODE, wasAscii)
    }
}

internal fun RimeEngine.exportUserDictSnapshotsOnEngine(): List<File>? {
    requireEngine()
    releaseSession()
    if (!RimeNative.nativeLeversAvailable() || !RimeNative.nativeLeversPrepareInstallation()) {
        return null
    }
    val syncDir = RimeNative.nativeUserDataSyncDir()?.let(::File) ?: return null
    val names = RimeNative.nativeUserDictList() ?: return null
    val snapshots = ArrayList<File>()
    for (name in names.filterNotNull().filter { it.isNotEmpty() && !it.startsWith(".") }) {
        if (!RimeNative.nativeBackupUserDict(name)) return null
        val file = File(syncDir, name + RimeEngine.USERDB_SNAPSHOT_EXTENSION)
        if (!file.isFile || file.length() == 0L) return null
        snapshots += file
    }
    return snapshots
}

internal fun RimeEngine.mergeUserDictSnapshotsOnEngine(snapshots: List<File>): Boolean {
    requireEngine()
    releaseSession()
    if (!RimeNative.nativeLeversAvailable() || !RimeNative.nativeLeversPrepareInstallation()) {
        return false
    }
    return snapshots.all { RimeNative.nativeRestoreUserDict(it.path) }
}

internal fun RimeEngine.restoreSessionAfterMaintenance(
    wantedSchemaId: String,
    options: List<Pair<String, Boolean>>,
) {
    val created = RimeNative.nativeSessionCreate(engine)
    if (created == 0L) {
        if (engine != 0L) RimeNative.nativeEngineDestroy(engine)
        engine = 0L
        throw RimeException(
            RimeException.Kind.OPERATION_FAILED,
            "cannot recreate session after maintenance; the engine was closed",
        )
    }
    session = created
    if (wantedSchemaId.isNotEmpty()) RimeNative.nativeSelectSchema(engine, session, wantedSchemaId)
    for ((name, value) in options) RimeNative.nativeSetOption(engine, session, name, value)
}

internal fun RimeEngine.cancelCompositionOnEngine(): RimeSnapshot {
    requireEngine()
    if (session == 0L) {
        throw RimeException(RimeException.Kind.NO_SESSION, "cancel requested without a session")
    }
    RimeNative.nativeClearComposition(engine, session)
    // The cancel is the recovery: the read below decides whether the engine is usable again. Left
    // set, the flag would make that read refuse and keep input dead for good.
    snapshotRecoveryRequired = false
    val state =
        snapshotOnEngine()
            ?: throw RimeException(
                RimeException.Kind.SNAPSHOT_UNAVAILABLE,
                "engine state unreadable after cancel",
            )
    if (state.isComposing || state.input.isNotEmpty() || state.commitText.isNotEmpty()) {
        snapshotRecoveryRequired = true
        throw RimeException(
            RimeException.Kind.SNAPSHOT_UNAVAILABLE,
            "composition still active after cancel",
        )
    }
    snapshotRecoveryRequired = false
    return state
}
