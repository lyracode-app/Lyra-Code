package com.yukisoffd.lyracode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRuntimeOwnerTest {
    @Test
    fun retainsRuntimeAndIgnoresStaleDetach() {
        val owner = ProcessRuntimeOwner<Any, Any>()
        val first = Any()
        val second = Any()
        val runtime = owner.getOrCreate { Any() }
        owner.attach(first)
        owner.attach(second)
        owner.detach(first)
        assertTrue(owner.isAttached(second))
        assertSame(runtime, owner.getOrCreate { error("runtime recreated") })
        owner.detach(second)
        assertFalse(owner.isAttached(second))
        assertSame(runtime, owner.getOrCreate { error("runtime recreated") })
    }
}
