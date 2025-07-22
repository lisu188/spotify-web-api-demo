package com.lis.spotify.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressStoreTest {
  @Test
  fun storeLifecycle() {
    val store = ProgressStore()
    store.create("1")
    store.update("1", 50)
    assertEquals(50, store.get("1")!!.percent)
    store.complete("1")
    assertEquals(100, store.get("1")!!.percent)
    store.fail("1", "err")
    assertEquals("ERROR", store.get("1")!!.status.name)
  }
}
