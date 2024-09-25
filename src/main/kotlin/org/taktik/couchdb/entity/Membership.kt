package org.taktik.couchdb.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Membership(
	@field:JsonProperty("all_nodes") val allNodes: List<String> = emptyList(),
	@field:JsonProperty("cluster_nodes") val clusterNodes: List<String> = emptyList()
)