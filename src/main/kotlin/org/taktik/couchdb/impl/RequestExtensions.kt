/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.taktik.couchdb.impl

import io.icure.asyncjacksonhttpclient.net.web.Request
import io.icure.asyncjacksonhttpclient.net.web.Response
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.reactor.mono
import org.taktik.couchdb.HeaderHandler
import reactor.core.publisher.Mono

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Request.retrieveAndInjectRequestId(
	headerHandlers: Map<String, HeaderHandler>,
	timingHandler: ((Long, Map<String, List<String>>) -> Mono<Unit>)?
): Response = this.retrieve().let {
	headerHandlers.entries.fold(it) { resp, (header, handler) ->
		resp.onHeader(header) { value -> mono { handler.handle(value) } }
	}.let { resp ->
		timingHandler?.let { handler ->
			resp.withTiming(handler)
		} ?: resp
	}
}
