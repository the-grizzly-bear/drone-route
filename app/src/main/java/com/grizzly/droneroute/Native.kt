package com.grizzly.droneroute

object Native {
    init { System.loadLibrary("droneroute") }
    @JvmStatic external fun info(log: ByteArray): String

    @JvmStatic external fun keychains(log: ByteArray, apiKey: String): String
    @JvmStatic external fun track(log: ByteArray, keychains: String?): String
}
