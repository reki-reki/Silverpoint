package app.silverpoint

import android.app.Application

/** Holds what must outlive a screen: the develop queue. */
class SilverpointApp : Application() {
    val developer: Developer by lazy { Developer(this) }
}
