package com.gempukku.context.processor.inject.decorator

import com.gempukku.context.processor.inject.InjectionException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class WorkerThreadExecutorSystem : SystemDecorator {
    private val singleThreadFactory = SingleThreadFactory()
    private val resultsToCopy: MutableList<Pair<Future<Future<Any>>, CompletableFuture<Any>>> =
        Collections.synchronizedList(LinkedList())

    val executorService: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(singleThreadFactory).also {
            // Ensure thread is created
            it.execute {}
        }

    init {
        executorService.scheduleAtFixedRate({
            synchronized(resultsToCopy) {
                val iterator = resultsToCopy.iterator()
                while (iterator.hasNext()) {
                    val resultCopy = iterator.next()
                    if (resultCopy.first.isDone) {
                        val innerFuture = resultCopy.first.get() as Future<Any>
                        if (innerFuture.isDone) {
                            val value = innerFuture.get()
                            resultCopy.second.complete(value)
                            iterator.remove()
                        }
                    }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    override fun <T> decorate(system: T, systemClass: Class<T>): T {
        val methodsThatCanBeOffloadedToWorkerThread = mutableSetOf<Method>()

        systemClass.declaredMethods.forEach { method ->
            if (method.returnType == Void.TYPE || method.returnType == Future::class.java) {
                methodsThatCanBeOffloadedToWorkerThread.add(method)
            }
        }

        val handler = InvocationHandler { _, method, args ->
            if (Thread.currentThread() == singleThreadFactory.singleThread) {
                callMethod(system, method, args)
            } else {
                if (methodsThatCanBeOffloadedToWorkerThread.contains(method)) {
                    if (method.returnType == Void.TYPE) {
                        executorService.execute {
                            callMethod(system, method, args)
                        }
                    } else {
                        val completableFuture = CompletableFuture<Any>()
                        val callMethodCallable = Callable<Any> {
                            callMethod(system, method, args)
                        }
                        val future = executorService.submit(callMethodCallable)
                        resultsToCopy.add((future as Future<Future<Any>>) to completableFuture)
                        completableFuture
                    }
                } else {
                    throw InjectionException(
                        "Unable to offload method ${method.declaringClass.name}::${method.name} to separate Thread, " +
                                "method must return void/Unit or Future object"
                    )
                }
            }
        }

        return Proxy.newProxyInstance(systemClass.classLoader, arrayOf(systemClass), handler) as T
    }

    private fun <T> callMethod(system: T, method: Method, args: Array<out Any>?): Any? =
        if (args == null) {
            method.invoke(system)
        } else {
            method.invoke(system, args)
        }
}

private class SingleThreadFactory : ThreadFactory {
    var singleThread: Thread? = null

    override fun newThread(r: Runnable): Thread {
        return Thread(r).also { singleThread = it }
    }
}