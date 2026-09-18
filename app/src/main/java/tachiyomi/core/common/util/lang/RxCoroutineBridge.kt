package tachiyomi.core.common.util.lang

import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscriber
import kotlinx.coroutines.CancellableContinuation

/*
 * Util functions for bridging RxJava and coroutines. Taken from TachiyomiEH/SY.
 * (Rewritten without kotlinx-coroutines' internal resume helpers so Hikari can
 * compile against a stable coroutines release.)
 */

suspend fun <T> Observable<T>.awaitSingle(): T = single().awaitOne()

private suspend fun <T> Observable<T>.awaitOne(): T = suspendCancellableCoroutine { cont ->
    val subscription = subscribe(
        object : Subscriber<T>() {
            override fun onStart() {
                request(1)
            }

            override fun onNext(t: T) {
                if (cont.isActive) {
                    cont.resume(t)
                }
            }

            override fun onCompleted() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("Should have invoked onNext"),
                    )
                }
            }

            override fun onError(e: Throwable) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }
        },
    )
    cont.unsubscribeOnCancellation(subscription)
}

private fun <T> CancellableContinuation<T>.unsubscribeOnCancellation(sub: rx.Subscription) {
    invokeOnCancellation { sub.unsubscribe() }
}
