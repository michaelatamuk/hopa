package com.hopa.app.backend

/**
 * The name a launch config gave this phone. The gateway's device name is the
 * build model and is fixed when the session is built, so a device test uses
 * this only to label the phone on HOPA's own screen. It is never persisted.
 */
object BackendLaunchName {
    @Volatile
    var value: String = ""
}
