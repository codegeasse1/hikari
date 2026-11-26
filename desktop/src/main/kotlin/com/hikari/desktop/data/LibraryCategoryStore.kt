package com.hikari.desktop.data

import com.google.gson.Gson
import java.security.MessageDigest
import java.util.Base64
import java.util.prefs.Preferences

internal data class LibraryCategory(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
) {
    companion object {
        const val MOVIES = "cat-movies"
        const val SERIES = "cat-series"
        const val ACTION = "cat-action"
        const val ROMANCE = "cat-romance"

        val DEFAULTS = listOf(
            LibraryCategory(MOVIES, "Movies", builtIn = true),
            LibraryCategory(SERIES, "Series", builtIn = true),
            LibraryCategory(ACTION, "Action", builtIn = true),
            LibraryCategory(ROMANCE, "Romance", builtIn = true),
        )
    }
}

internal class LibraryCategoryStore(
    preferences: Preferences = Preferences.userNodeForPackage(LibraryCategoryStore::class.java),
) {
    private val categoryNodes = preferences.node(CATEGORIES_NODE)
    private val assignmentNodes = preferences.node(ASSIGNMENTS_NODE)

    @Synchronized
    fun categories(): List<LibraryCategory> {
        if (!categoryNodes.getBoolean(INITIALIZED_KEY, false)) {
            LibraryCategory.DEFAULTS.forEachIndexed { index, category ->
                writeCategory(category, index.toLong())
            }
            categoryNodes.putBoolean(INITIALIZED_KEY, true)
            categoryNodes.flush()
        }

        return categoryNodes.childrenNames()
            .mapNotNull { childName ->
                val node = categoryNodes.node(childName)
                val id = node.get(ID_KEY, "")
                val name = node.get(NAME_KEY, "").trim()
                if (id.isBlank() || name.isBlank()) null
                else CategoryEntry(
                    order = node.getLong(ORDER_KEY, Long.MAX_VALUE),
                    category = LibraryCategory(id, name, node.getBoolean(BUILT_IN_KEY, false)),
                )
            }
            .sortedWith(compareBy<CategoryEntry> { it.order }.thenBy { it.category.name })
            .map(CategoryEntry::category)
    }

    @Synchronized
    fun create(name: String): LibraryCategory {
        val normalized = validateName(name)
        val existing = categories()
        require(existing.none { it.name.equals(normalized, ignoreCase = true) }) {
            "A category with this name already exists."
        }
        val nextOrder = categoryNodes.childrenNames()
            .maxOfOrNull { categoryNodes.node(it).getLong(ORDER_KEY, -1L) }
            ?.plus(1L) ?: 0L
        val category = LibraryCategory(id = "cat-${java.util.UUID.randomUUID()}", name = normalized)
        writeCategory(category, nextOrder)
        categoryNodes.flush()
        return category
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val normalized = validateName(name)
        val existing = categories()
        val nodeName = categoryNodeName(id)
        require(categoryNodes.nodeExists(nodeName)) { "Category not found." }
        require(existing.none { it.id != id && it.name.equals(normalized, ignoreCase = true) }) {
            "A category with this name already exists."
        }
        categoryNodes.node(nodeName).put(NAME_KEY, normalized)
        categoryNodes.node(nodeName).flush()
    }

    @Synchronized
    fun delete(id: String) {
        categories()
        val nodeName = categoryNodeName(id)
        if (categoryNodes.nodeExists(nodeName)) {
            categoryNodes.node(nodeName).removeNode()
            categoryNodes.flush()
        }
        assignmentNodes.childrenNames().forEach { childName ->
            val assignment = assignmentNodes.node(childName)
            val remaining = readCategoryIds(assignment.get(CATEGORY_IDS_KEY, EMPTY_JSON)) - id
            if (remaining.isEmpty()) assignment.removeNode()
            else assignment.put(CATEGORY_IDS_KEY, gson.toJson(remaining.sorted()))
        }
        assignmentNodes.flush()
    }

    @Synchronized
    fun assign(title: AddonTitle, categoryIds: Set<String>) {
        val validIds = categoryIds intersect categories().mapTo(mutableSetOf(), LibraryCategory::id)
        val nodeName = title.savedTitleIdentity().storageKey()
        if (validIds.isEmpty()) {
            if (assignmentNodes.nodeExists(nodeName)) assignmentNodes.node(nodeName).removeNode()
            assignmentNodes.flush()
            return
        }

        val node = assignmentNodes.node(nodeName)
        node.put(IDENTITY_KEY, gson.toJson(title.savedTitleIdentity()))
        node.put(CATEGORY_IDS_KEY, gson.toJson(validIds.sorted()))
        node.flush()
        assignmentNodes.flush()
    }

    @Synchronized
    fun assignments(): Map<SavedTitleIdentity, Set<String>> {
        val validIds = categories().mapTo(mutableSetOf(), LibraryCategory::id)
        return assignmentNodes.childrenNames().mapNotNull { childName ->
            val node = assignmentNodes.node(childName)
            val identity = runCatching {
                gson.fromJson(node.get(IDENTITY_KEY, EMPTY_JSON), SavedTitleIdentity::class.java)
            }.getOrNull() ?: return@mapNotNull null
            val assigned = readCategoryIds(node.get(CATEGORY_IDS_KEY, EMPTY_JSON)) intersect validIds
            if (assigned.isEmpty()) null else identity to assigned
        }.toMap()
    }

    @Synchronized
    fun removeTitle(title: AddonTitle) {
        val nodeName = title.savedTitleIdentity().storageKey()
        if (assignmentNodes.nodeExists(nodeName)) assignmentNodes.node(nodeName).removeNode()
        assignmentNodes.flush()
    }

    private fun writeCategory(category: LibraryCategory, order: Long) {
        val node = categoryNodes.node(categoryNodeName(category.id))
        node.put(ID_KEY, category.id)
        node.put(NAME_KEY, category.name)
        node.putBoolean(BUILT_IN_KEY, category.builtIn)
        node.putLong(ORDER_KEY, order)
        node.flush()
    }

    private fun categoryNodeName(id: String): String = id

    private fun validateName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "Category name cannot be blank." }
    }

    private fun readCategoryIds(encoded: String): Set<String> =
        runCatching { gson.fromJson(encoded, Array<String>::class.java)?.toSet().orEmpty() }
            .getOrDefault(emptySet())

    private data class CategoryEntry(val order: Long, val category: LibraryCategory)

    private companion object {
        const val CATEGORIES_NODE = "libraryCategories"
        const val ASSIGNMENTS_NODE = "libraryCategoryAssignments"
        const val INITIALIZED_KEY = "initialized"
        const val ID_KEY = "id"
        const val NAME_KEY = "name"
        const val BUILT_IN_KEY = "builtIn"
        const val ORDER_KEY = "order"
        const val IDENTITY_KEY = "identity"
        const val CATEGORY_IDS_KEY = "categoryIds"
        const val EMPTY_JSON = "{}"
        val gson = Gson()
    }
}

internal fun filterSavedTitlesByCategory(
    titles: List<AddonTitle>,
    categoryId: String?,
    assignments: Map<SavedTitleIdentity, Set<String>>,
): List<AddonTitle> = if (categoryId == null) titles else titles.filter { title ->
    categoryId in assignments[title.savedTitleIdentity()].orEmpty()
}

private fun SavedTitleIdentity.storageKey(): String {
    val bytes = gson.toJson(this).toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private val gson = Gson()
