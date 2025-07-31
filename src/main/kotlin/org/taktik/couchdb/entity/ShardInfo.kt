package org.taktik.couchdb.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShardInfo(
	/**
	 * Shard partition -> Replicas location (nods). Example: {"00000000-ffffffff":["couchdb@couchdb.three"]}
	 */
	val shards: Map<String, Set<String>>
)