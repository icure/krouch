package org.taktik.couchdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusterManagementTests : CouchDbClientTestBase() {
	@Test
	fun testGetAndChangeConfig(): Unit = runBlocking {
		val node = defaultClient.membership().clusterNodes.first()
		assertNull(defaultClient.getConfigOption(node, "chttpd", "enable_xframe_options"))
		defaultClient.setConfigOption(node, "chttpd", "enable_xframe_options", "true")
		assertEquals(defaultClient.getConfigOption(node, "chttpd", "enable_xframe_options"), "true")
		defaultClient.deleteConfigOption(node, "chttpd", "enable_xframe_options")
		assertNull(defaultClient.getConfigOption(node, "chttpd", "enable_xframe_options"))
	}
}