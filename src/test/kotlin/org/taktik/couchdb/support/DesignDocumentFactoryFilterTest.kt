package org.taktik.couchdb.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.taktik.couchdb.annotation.View
import org.taktik.couchdb.annotation.Views
import org.taktik.couchdb.support.generators.views.SimpleViewGenerator
import org.taktik.couchdb.support.generators.views.ViewGenerator
import java.lang.reflect.AnnotatedElement

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IncludeInDdoc

private val onlyIncludeInDdoc: (AnnotatedElement) -> Boolean = { it.isAnnotationPresent(IncludeInDdoc::class.java) }

private const val MAP = "function(doc) { emit(doc._id, null); }"

/**
 * Class-level views and some method-level views are marked with [IncludeInDdoc].
 */
@IncludeInDdoc
@Views(
	View(name = "class_view", map = MAP),
	View(name = "class_view_partitioned", map = MAP, secondaryPartition = "part"),
)
open class AnnotatedRepository {
	@IncludeInDdoc
	@View(name = "included_method_view", map = MAP)
	fun includedMethodView() {}

	@IncludeInDdoc
	@View(name = "included_method_view_partitioned", map = MAP, secondaryPartition = "part")
	fun includedMethodViewPartitioned() {}

	@View(name = "excluded_method_view", map = MAP)
	fun excludedMethodView() {}

	@View(name = "excluded_method_view_other_partition", map = MAP, secondaryPartition = "other")
	fun excludedMethodViewOtherPartition() {}
}

/**
 * The class itself is not marked, so its class-level views must be excluded, while the marked method is kept.
 * The views inherited from [AnnotatedRepository] are filtered with the same rules.
 */
@Views(
	View(name = "excluded_subclass_view", map = MAP),
)
class PartiallyAnnotatedRepository : AnnotatedRepository() {
	@IncludeInDdoc
	@View(name = "included_subclass_method_view", map = MAP)
	fun includedSubclassMethodView() {}

	@View(name = "excluded_subclass_method_view", map = MAP)
	fun excludedSubclassMethodView() {}
}

/**
 * Nothing is marked with [IncludeInDdoc]: everything must be filtered out.
 */
@Views(
	View(name = "class_view", map = MAP),
	View(name = "class_view_partitioned", map = MAP, secondaryPartition = "part"),
)
class NotAnnotatedRepository {
	@View(name = "method_view", map = MAP)
	fun methodView() {}
}

class DesignDocumentFactoryFilterTest {

	private fun SimpleViewGenerator.viewNames(repository: Any) =
		generateViews(repository, "Entity").keys

	@Test
	fun `default filter keeps every view`() {
		val keys = SimpleViewGenerator().viewNames(AnnotatedRepository())
		assertEquals(
			setOf(
				ViewGenerator.ViewKey(partition = "", viewName = "class_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "class_view_partitioned"),
				ViewGenerator.ViewKey(partition = "", viewName = "included_method_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "included_method_view_partitioned"),
				ViewGenerator.ViewKey(partition = "", viewName = "excluded_method_view"),
				ViewGenerator.ViewKey(partition = "other", viewName = "excluded_method_view_other_partition"),
			),
			keys
		)
	}

	@Test
	fun `only views declared on annotated classes and methods are generated`() {
		val keys = SimpleViewGenerator(filterMetadataSources = onlyIncludeInDdoc).viewNames(AnnotatedRepository())
		assertEquals(
			setOf(
				ViewGenerator.ViewKey(partition = "", viewName = "class_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "class_view_partitioned"),
				ViewGenerator.ViewKey(partition = "", viewName = "included_method_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "included_method_view_partitioned"),
			),
			keys
		)
	}

	@Test
	fun `filter is applied to each class and method independently, including superclasses`() {
		val keys = SimpleViewGenerator(filterMetadataSources = onlyIncludeInDdoc).viewNames(PartiallyAnnotatedRepository())
		assertEquals(
			setOf(
				ViewGenerator.ViewKey(partition = "", viewName = "included_subclass_method_view"),
				ViewGenerator.ViewKey(partition = "", viewName = "class_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "class_view_partitioned"),
				ViewGenerator.ViewKey(partition = "", viewName = "included_method_view"),
				ViewGenerator.ViewKey(partition = "part", viewName = "included_method_view_partitioned"),
			),
			keys
		)
	}

	@Test
	fun `design docs only contain views from annotated classes and methods`() {
		val ddocs = DesignDocumentFactory.getStdDesignDocumentFactory(filterMetadataSources = onlyIncludeInDdoc).generateFrom(
			designDocEntityName = "Entity",
			targetPartition = DesignDocumentFactory.TargetPartition.All,
			metaDataSource = AnnotatedRepository(),
			useVersioning = false
		).associateBy { it.id }

		// The "other" partition only contained filtered-out views, so no ddoc is generated for it.
		assertEquals(setOf("_design/Entity", "_design/Entity-part"), ddocs.keys)
		assertEquals(setOf("class_view", "included_method_view"), ddocs.getValue("_design/Entity").views.keys)
		assertEquals(
			setOf("class_view_partitioned", "included_method_view_partitioned"),
			ddocs.getValue("_design/Entity-part").views.keys
		)
	}

	@Test
	fun `filtering changes the versioned ddoc id`() {
		fun generate(filter: (AnnotatedElement) -> Boolean) =
			DesignDocumentFactory.getStdDesignDocumentFactory(filterMetadataSources = filter).generateFrom(
				designDocEntityName = "Entity",
				targetPartition = DesignDocumentFactory.TargetPartition.Unpartitioned,
				metaDataSource = AnnotatedRepository(),
				useVersioning = true
			).single().id

		val unfiltered = generate { true }
		val filtered = generate(onlyIncludeInDdoc)
		assertTrue(unfiltered.startsWith("_design/Entity_"))
		assertTrue(filtered.startsWith("_design/Entity_"))
		assertTrue(unfiltered != filtered, "Different view sets must produce different version hashes")
	}

	@Test
	fun `when every view is filtered out no view is generated`() {
		val keys = SimpleViewGenerator(filterMetadataSources = onlyIncludeInDdoc).viewNames(NotAnnotatedRepository())
		assertTrue(keys.isEmpty())

		val rejectAll = SimpleViewGenerator(filterMetadataSources = { false }).viewNames(AnnotatedRepository())
		assertTrue(rejectAll.isEmpty())
	}

	@Test
	fun `when every view is filtered out no design doc is generated`() {
		val factory = DesignDocumentFactory.getStdDesignDocumentFactory(filterMetadataSources = onlyIncludeInDdoc)
		listOf(
			DesignDocumentFactory.TargetPartition.All,
			DesignDocumentFactory.TargetPartition.Unpartitioned,
			DesignDocumentFactory.TargetPartition.Partition("part"),
		).forEach { targetPartition ->
			val ddocs = factory.generateFrom(
				designDocEntityName = "Entity",
				targetPartition = targetPartition,
				metaDataSource = NotAnnotatedRepository(),
				useVersioning = true
			)
			assertTrue(ddocs.isEmpty(), "Expected no design doc for $targetPartition but got ${ddocs.map { it.id }}")
		}
	}
}
