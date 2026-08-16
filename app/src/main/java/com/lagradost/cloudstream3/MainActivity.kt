package com.lagradost.cloudstream3

import com.lagradost.nicehttp.Requests

/** Global `app` object plugins use — resolves to `MainActivityKt.getApp()`. */
var app: Requests = Requests()

var insecureApp: Requests = Requests()
