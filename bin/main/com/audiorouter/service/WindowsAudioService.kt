package com.audiorouter.service

import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

private val log = KotlinLogging.logger {}

/**
 * Windows audio backend using WASAPI (via JNA) and javax.sound.sampled.
 *
 * Virtual channel routing is supported when VB-Cable
 * (https://vb-audio.com/Cable/) is installed — its virtual inputs are used
 * as the AudioRouter channel sinks.  Without VB-Cable the app operates in
 * volume-only mode: per-channel volume and mute still work, but audio
 * streams are not physically re-routed between devices.
 *
 * Level monitoring uses javax.sound TargetDataLine.  For true loopback
 * capture enable "Stereo Mix" in Windows Sound settings.
 */
class WindowsAudioService : AudioService {

    // ── WASAPI GUIDs ─────────────────────────────────────────────────────
    private val CLSID_MMDeviceEnumerator = GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_IMMDeviceEnumerator  = GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_IAudioEndpointVolume = GUID("{5CDF2C82-841E-4546-9722-0CF74078229A}")
    private val IID_IAudioSessionManager2 = GUID("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}")

    // eRender=0, eCapture=1; DEVICE_STATE_ACTIVE=1; eConsole=0
    private val DATAFLOW_RENDER = 0
    private val DATAFLOW_CAPTURE = 1
    private val STATE_ACTIVE = 1
    private val ROLE_CONSOLE = 0

    // In-memory store of "virtual channel" handle → Windows device id
    private val channelDeviceMap = mutableMapOf<AudioChannel, String>()
    private var nextHandle = 1

    // Fake module registry (Windows has no pactl modules)
    private val fakeModules = mutableMapOf<Int, String>()

    init {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, 0) // COINIT_MULTITHREADED
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun createEnumerator(): Pointer? {
        val ppv = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_MMDeviceEnumerator, null, 1 /* CLSCTX_INPROC_SERVER */,
            IID_IMMDeviceEnumerator, ppv
        )
        return if (COMUtils.SUCCEEDED(hr)) ppv.value else null
    }

    /** Returns list of (deviceId, friendlyName) for active render endpoints. */
    private fun wasapiListRenderDevices(): List<Pair<String, String>> {
        val enumeratorPtr = createEnumerator() ?: return emptyList()
        return try {
            val enumerator = IMMDeviceEnumerator(enumeratorPtr)
            val collectionRef = PointerByReference()
            val hr = enumerator.EnumAudioEndpoints(DATAFLOW_RENDER, STATE_ACTIVE, collectionRef)
            if (!COMUtils.SUCCEEDED(hr)) return emptyList()

            val collection = IMMDeviceCollection(collectionRef.value)
            val countRef = IntByReference()
            collection.GetCount(countRef)
            val count = countRef.value

            (0 until count).mapNotNull { i ->
                val deviceRef = PointerByReference()
                collection.Item(i, deviceRef)
                val device = IMMDevice(deviceRef.value)
                val idRef = PointerByReference()
                device.GetId(idRef)
                val id = idRef.value?.getWideString(0) ?: return@mapNotNull null

                // Get friendly name via property store
                val propStoreRef = PointerByReference()
                device.OpenPropertyStore(0 /* STGM_READ */, propStoreRef)
                val propStore = IPropertyStore(propStoreRef.value)
                val pv = PROPVARIANT()
                // PKEY_Device_FriendlyName = {a45c254e-df1c-4efd-8020-67d146a850e0}, pid=14
                val key = PROPERTYKEY()
                key.fmtid = GUID("{a45c254e-df1c-4efd-8020-67d146a850e0}")
                key.pid = 14
                propStore.GetValue(key, pv)
                val name = pv.pwszVal?.toString() ?: id
                id to name
            }
        } finally {
            enumeratorPtr.let { Unknown(it).Release() }
        }
    }

    private fun wasapiListCaptureDevices(): List<Pair<String, String>> {
        val enumeratorPtr = createEnumerator() ?: return emptyList()
        return try {
            val enumerator = IMMDeviceEnumerator(enumeratorPtr)
            val collectionRef = PointerByReference()
            val hr = enumerator.EnumAudioEndpoints(DATAFLOW_CAPTURE, STATE_ACTIVE, collectionRef)
            if (!COMUtils.SUCCEEDED(hr)) return emptyList()
            val collection = IMMDeviceCollection(collectionRef.value)
            val countRef = IntByReference()
            collection.GetCount(countRef)
            (0 until countRef.value).mapNotNull { i ->
                val deviceRef = PointerByReference()
                collection.Item(i, deviceRef)
                val device = IMMDevice(deviceRef.value)
                val idRef = PointerByReference()
                device.GetId(idRef)
                val id = idRef.value?.getWideString(0) ?: return@mapNotNull null
                val propStoreRef = PointerByReference()
                device.OpenPropertyStore(0, propStoreRef)
                val propStore = IPropertyStore(propStoreRef.value)
                val pv = PROPVARIANT()
                val key = PROPERTYKEY()
                key.fmtid = GUID("{a45c254e-df1c-4efd-8020-67d146a850e0}")
                key.pid = 14
                propStore.GetValue(key, pv)
                id to (pv.pwszVal?.toString() ?: id)
            }
        } finally {
            enumeratorPtr.let { Unknown(it).Release() }
        }
    }

    private fun getEndpointVolume(deviceId: String): IAudioEndpointVolume? {
        val enumeratorPtr = createEnumerator() ?: return null
        return try {
            val enumerator = IMMDeviceEnumerator(enumeratorPtr)
            val deviceRef = PointerByReference()
            enumerator.GetDevice(deviceId, deviceRef)
            val device = IMMDevice(deviceRef.value)
            val volRef = PointerByReference()
            device.Activate(IID_IAudioEndpointVolume, 1, null, volRef)
            IAudioEndpointVolume(volRef.value)
        } catch (e: Exception) {
            log.warn { "getEndpointVolume failed: ${e.message}" }
            null
        } finally {
            enumeratorPtr.let { Unknown(it).Release() }
        }
    }

    // ── AudioService implementation ───────────────────────────────────────

    override suspend fun listAllSinks(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        wasapiListRenderDevices().mapIndexed { i, (_, name) -> i to name }
    }

    override suspend fun listRealSinks(): List<Pair<Int, String>> = listAllSinks()

    override suspend fun listRealSources(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        wasapiListCaptureDevices().mapIndexed { i, (_, name) -> i to name }
    }

    override suspend fun loadLoopbackFromSource(sourceName: String, sinkName: String): Int = -1
    override suspend fun loadEqSink(channel: AudioChannel, gains: List<Float>, masterSinkName: String): Int = -1

    override suspend fun loadNullSink(channel: AudioChannel): Int {
        // Map the channel to a VB-Cable device if present, otherwise the default output
        val devices = withContext(Dispatchers.IO) { wasapiListRenderDevices() }
        val vbDevice = devices.firstOrNull { (_, name) ->
            name.contains("CABLE Input", ignoreCase = true) ||
            name.contains("VB-Audio", ignoreCase = true)
        }
        val chosen = vbDevice?.first ?: devices.firstOrNull()?.first ?: ""
        channelDeviceMap[channel] = chosen

        val handle = nextHandle++
        fakeModules[handle] = "AudioRouter_${channel.sinkSuffix} null-sink → $chosen"
        log.info { "Virtual channel ${channel.displayName} mapped to: $chosen (handle=$handle)" }
        return handle
    }

    override suspend fun loadLoopback(channel: AudioChannel, outputSinkName: String): Int {
        val handle = nextHandle++
        fakeModules[handle] = "AudioRouter_${channel.sinkSuffix} loopback → $outputSinkName"
        return handle
    }

    override suspend fun unloadModule(id: Int): Boolean {
        fakeModules.remove(id)
        channelDeviceMap.entries.removeIf { false } // loopback handles are ephemeral
        return true
    }

    override suspend fun listShortModules(): List<String> =
        fakeModules.entries.map { (id, name) -> "$id\t$name" }

    override suspend fun listSinkInputs(): List<AudioStream> = withContext(Dispatchers.IO) {
        enumerateWindowsSessions()
    }

    override suspend fun moveSinkInput(sinkInputId: Int, sinkName: String): Boolean {
        // On Windows, routing a running session to a different device requires
        // the undocumented IPolicyConfig COM interface. We attempt it here; if the
        // COM object is unavailable the assignment is saved as a rule but audio
        // continues on its current device until the application restarts.
        log.info { "Windows: routing session $sinkInputId → $sinkName (best-effort)" }
        return trySetSessionDefaultEndpoint(sinkInputId, sinkName)
    }

    override suspend fun setSinkVolume(sinkName: String, percent: Int): Boolean {
        val deviceId = channelDeviceMap.entries
            .firstOrNull { (ch, _) -> ch.sinkName == sinkName }?.value
            ?: return false
        val vol = getEndpointVolume(deviceId) ?: return false
        return COMUtils.SUCCEEDED(vol.SetMasterVolumeLevelScalar(percent / 100f, null))
    }

    override suspend fun setSinkMute(sinkName: String, muted: Boolean): Boolean {
        val deviceId = channelDeviceMap.entries
            .firstOrNull { (ch, _) -> ch.sinkName == sinkName }?.value
            ?: return false
        val vol = getEndpointVolume(deviceId) ?: return false
        return COMUtils.SUCCEEDED(vol.SetMute(if (muted) 1 else 0, null))
    }

    override fun startSubscribeProcess(): Process {
        // No pactl subscribe on Windows — return a process that emits periodic
        // refresh ticks so StreamMonitor keeps streams up to date.
        val script = """
            while (${'$'}true) {
                Write-Output "Event 'change' on sink-input #0"
                Start-Sleep -Seconds 3
            }
        """.trimIndent()
        return ProcessBuilder(
            "powershell", "-NonInteractive", "-Command", script
        ).redirectErrorStream(true).start()
    }

    override fun openLevelCapture(channel: AudioChannel): Process? {
        // Use javax.sound.sampled in a spawned thread piped via PipedStream
        // so the caller gets the same InputStream contract as Linux pacat.
        // Requires "Stereo Mix" or similar loopback device to be enabled in
        // Windows Sound settings; if unavailable returns null silently.
        val format = AudioFormat(22050f, 16, 2, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            log.info { "No loopback capture line available for ${channel.displayName}" }
            return null
        }
        return try {
            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format)
            line.start()
            // Wrap the TargetDataLine in a fake Process so callers can treat it uniformly
            TargetDataLineProcess(line)
        } catch (e: Exception) {
            log.warn { "openLevelCapture failed for ${channel.displayName}: ${e.message}" }
            null
        }
    }

    // ── Windows session enumeration ────────────────────────────────────────

    private fun enumerateWindowsSessions(): List<AudioStream> {
        val enumeratorPtr = createEnumerator() ?: return emptyList()
        return try {
            val enumerator = IMMDeviceEnumerator(enumeratorPtr)
            val deviceRef = PointerByReference()
            enumerator.GetDefaultAudioEndpoint(DATAFLOW_RENDER, ROLE_CONSOLE, deviceRef)
            val device = IMMDevice(deviceRef.value)
            val managerRef = PointerByReference()
            device.Activate(IID_IAudioSessionManager2, 1, null, managerRef)
            val manager = IAudioSessionManager2(managerRef.value)
            val enumRef = PointerByReference()
            manager.GetSessionEnumerator(enumRef)
            val sessionEnum = IAudioSessionEnumerator(enumRef.value)
            val countRef = IntByReference()
            sessionEnum.GetCount(countRef)

            (0 until countRef.value).mapNotNull { i ->
                val ctrlRef = PointerByReference()
                sessionEnum.GetSession(i, ctrlRef)
                val ctrl = IAudioSessionControl2(ctrlRef.value)
                val pidRef = IntByReference()
                ctrl.GetProcessId(pidRef)
                val pid = pidRef.value
                if (pid == 0) return@mapNotNull null // system session
                val name = try {
                    val ph = com.sun.jna.platform.win32.Kernel32.INSTANCE
                        .OpenProcess(0x0410 /* PROCESS_QUERY_INFO | VM_READ */, false, pid)
                    val buf = CharArray(260)
                    com.sun.jna.platform.win32.Psapi.INSTANCE.GetModuleFileNameExW(ph, null, buf, 260)
                    com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(ph)
                    String(buf).trimEnd(' ').substringAfterLast('\\').substringAfterLast('/')
                        .substringBeforeLast('.')
                } catch (_: Exception) { "pid:$pid" }
                AudioStream(i, name, name, pid, -1, null)
            }
        } catch (e: Exception) {
            log.warn { "enumerateWindowsSessions: ${e.message}" }
            emptyList()
        } finally {
            enumeratorPtr.let { Unknown(it).Release() }
        }
    }

    private fun trySetSessionDefaultEndpoint(sinkInputId: Int, sinkName: String): Boolean {
        // Uses undocumented IPolicyConfig — available on Windows 10/11.
        // CLSID: {870AF99C-171D-4F9E-AF0D-E63DF40C2BC9}
        // IID:   {F8679F50-850A-41CF-9C72-430F290290C8}
        return try {
            val devices = wasapiListRenderDevices()
            val deviceId = devices.firstOrNull { (_, name) ->
                name.equals(sinkName, ignoreCase = true) ||
                name.contains(sinkName, ignoreCase = true)
            }?.first ?: return false

            val clsid = GUID("{870AF99C-171D-4F9E-AF0D-E63DF40C2BC9}")
            val iid   = GUID("{F8679F50-850A-41CF-9C72-430F290290C8}")
            val ppv = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(clsid, null, 1, iid, ppv)
            if (!COMUtils.SUCCEEDED(hr)) return false

            val policy = IPolicyConfig(ppv.value)
            val wideId = Native.toCharArray(deviceId)
            COMUtils.SUCCEEDED(policy.SetDefaultEndpoint(wideId, ROLE_CONSOLE))
        } catch (e: Exception) {
            log.warn { "IPolicyConfig not available: ${e.message}" }
            false
        }
    }

    // ── Minimal JNA COM interface stubs ───────────────────────────────────

    private inner class IMMDeviceEnumerator(p: Pointer) : Unknown(p) {
        fun EnumAudioEndpoints(dataFlow: Int, stateMask: Int, ppDevices: PointerByReference): HRESULT =
            _invokeNativeObject(3, arrayOf(pointer, dataFlow, stateMask, ppDevices), HRESULT::class.java) as HRESULT
        fun GetDefaultAudioEndpoint(dataFlow: Int, role: Int, ppEndpoint: PointerByReference): HRESULT =
            _invokeNativeObject(4, arrayOf(pointer, dataFlow, role, ppEndpoint), HRESULT::class.java) as HRESULT
        fun GetDevice(pwstrId: String, ppDevice: PointerByReference): HRESULT =
            _invokeNativeObject(5, arrayOf(pointer, Native.toCharArray(pwstrId), ppDevice), HRESULT::class.java) as HRESULT
    }

    private inner class IMMDeviceCollection(p: Pointer) : Unknown(p) {
        fun GetCount(pcDevices: IntByReference): HRESULT =
            _invokeNativeObject(3, arrayOf(pointer, pcDevices), HRESULT::class.java) as HRESULT
        fun Item(nDevice: Int, ppDevice: PointerByReference): HRESULT =
            _invokeNativeObject(4, arrayOf(pointer, nDevice, ppDevice), HRESULT::class.java) as HRESULT
    }

    private inner class IMMDevice(p: Pointer) : Unknown(p) {
        fun Activate(iid: GUID, clsCtx: Int, pActivationParams: Pointer?, ppInterface: PointerByReference): HRESULT =
            _invokeNativeObject(3, arrayOf(pointer, iid, clsCtx, pActivationParams, ppInterface), HRESULT::class.java) as HRESULT
        fun GetId(ppstrId: PointerByReference): HRESULT =
            _invokeNativeObject(5, arrayOf(pointer, ppstrId), HRESULT::class.java) as HRESULT
        fun OpenPropertyStore(stgmAccess: Int, ppProperties: PointerByReference): HRESULT =
            _invokeNativeObject(6, arrayOf(pointer, stgmAccess, ppProperties), HRESULT::class.java) as HRESULT
    }

    private inner class IPropertyStore(p: Pointer) : Unknown(p) {
        fun GetValue(key: PROPERTYKEY, pv: PROPVARIANT): HRESULT =
            _invokeNativeObject(5, arrayOf(pointer, key, pv), HRESULT::class.java) as HRESULT
    }

    private inner class IAudioEndpointVolume(p: Pointer) : Unknown(p) {
        fun SetMasterVolumeLevelScalar(fLevel: Float, pguid: Pointer?): HRESULT =
            _invokeNativeObject(7, arrayOf(pointer, fLevel, pguid), HRESULT::class.java) as HRESULT
        fun SetMute(bMute: Int, pguid: Pointer?): HRESULT =
            _invokeNativeObject(9, arrayOf(pointer, bMute, pguid), HRESULT::class.java) as HRESULT
    }

    private inner class IAudioSessionManager2(p: Pointer) : Unknown(p) {
        fun GetSessionEnumerator(ppSessionList: PointerByReference): HRESULT =
            _invokeNativeObject(5, arrayOf(pointer, ppSessionList), HRESULT::class.java) as HRESULT
    }

    private inner class IAudioSessionEnumerator(p: Pointer) : Unknown(p) {
        fun GetCount(SessionCount: IntByReference): HRESULT =
            _invokeNativeObject(3, arrayOf(pointer, SessionCount), HRESULT::class.java) as HRESULT
        fun GetSession(SessionCount: Int, Session: PointerByReference): HRESULT =
            _invokeNativeObject(4, arrayOf(pointer, SessionCount, Session), HRESULT::class.java) as HRESULT
    }

    private inner class IAudioSessionControl2(p: Pointer) : Unknown(p) {
        fun GetProcessId(pRetVal: IntByReference): HRESULT =
            _invokeNativeObject(9, arrayOf(pointer, pRetVal), HRESULT::class.java) as HRESULT
    }

    private inner class IPolicyConfig(p: Pointer) : Unknown(p) {
        fun SetDefaultEndpoint(wszDeviceId: CharArray, eRole: Int): HRESULT =
            _invokeNativeObject(13, arrayOf(pointer, wszDeviceId, eRole), HRESULT::class.java) as HRESULT
    }

    @Structure.FieldOrder("vt", "wReserved1", "wReserved2", "wReserved3", "pwszVal")
    class PROPVARIANT : Structure() {
        @JvmField var vt: Short = 0
        @JvmField var wReserved1: Short = 0
        @JvmField var wReserved2: Short = 0
        @JvmField var wReserved3: Short = 0
        @JvmField var pwszVal: com.sun.jna.WString? = null
    }

    @Structure.FieldOrder("fmtid", "pid")
    class PROPERTYKEY : Structure() {
        @JvmField var fmtid: GUID = GUID()
        @JvmField var pid: Int = 0
    }
}

/** Wraps a [TargetDataLine] as a [Process] so LevelMonitor can treat it uniformly. */
private class TargetDataLineProcess(private val line: TargetDataLine) : Process() {
    override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
    override fun getInputStream() = object : java.io.InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (line.read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int) = line.read(b, off, len)
    }
    override fun getErrorStream() = java.io.InputStream.nullInputStream()
    override fun waitFor() = 0
    override fun exitValue() = 0
    override fun destroy() { line.stop(); line.close() }
}
