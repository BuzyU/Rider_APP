package com.ridervoice.errors

object ErrorHandler {

    fun handle(exception: Exception) {
        exception.printStackTrace()
    }
}
