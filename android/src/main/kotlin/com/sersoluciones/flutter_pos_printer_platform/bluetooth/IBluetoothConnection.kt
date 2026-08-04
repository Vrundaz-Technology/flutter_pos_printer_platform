package com.sersoluciones.flutter_pos_printer_platform.bluetooth
import android.os.Handler
import io.flutter.plugin.common.MethodChannel.Result

interface IBluetoothConnection {
    fun connect(address: String, result: Result)
    fun stop()
    fun write(out: ByteArray?)
    var state: Int

    /// Redirect this connection's events to [handler].
    ///
    /// The connection object outlives the engine that created it — it is held
    /// in a companion-object field and only replaced on an explicit
    /// disconnect. Capturing the handler once at construction meant whichever
    /// engine happened to connect first owned every later state change, read
    /// and write failure for the life of the link, even after that engine had
    /// detached. A background print isolate could therefore take ownership of
    /// the events the live UI needs.
    fun setHandler(handler: Handler)
}