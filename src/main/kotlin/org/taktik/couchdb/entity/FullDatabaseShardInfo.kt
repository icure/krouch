package org.taktik.couchdb.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FullDatabaseShardInfo(
	@param:JsonProperty("_id") val id: String,
	@param:JsonProperty("_rev") val rev: String? = null,
	@param:JsonProperty("shard_suffix") val shardSuffix: List<Int> = emptyList(),
	@param:JsonProperty("changelog") val changeLog: List<JsonNode> = emptyList(),
	@param:JsonProperty("by_node") val byNode: Map<String, List<String>> = emptyMap(),
	@param:JsonProperty("by_range") val byRange: Map<String, List<String>> = emptyMap(),
)
